/*******************************************************************************
 * Copyright (c) 2012-2016 Codenvy, S.A.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Codenvy, S.A. - initial API and implementation
 *******************************************************************************/
package org.eclipse.che.plugin.docker.machine;

import com.google.inject.assistedinject.Assisted;

import org.eclipse.che.api.core.NotFoundException;
import org.eclipse.che.api.core.model.machine.Command;
import org.eclipse.che.api.core.model.machine.Machine;
import org.eclipse.che.api.core.util.LineConsumer;
import org.eclipse.che.api.core.util.ListLineConsumer;
import org.eclipse.che.api.machine.server.exception.MachineException;
import org.eclipse.che.api.machine.server.impl.AbstractInstance;
import org.eclipse.che.api.machine.server.model.impl.MachineRuntimeInfoImpl;
import org.eclipse.che.api.machine.server.spi.Instance;
import org.eclipse.che.api.machine.server.spi.InstanceKey;
import org.eclipse.che.api.machine.server.spi.InstanceProcess;
import org.eclipse.che.commons.lang.NameGenerator;
import org.eclipse.che.plugin.docker.client.DockerConnector;
import org.eclipse.che.plugin.docker.client.Exec;
import org.eclipse.che.plugin.docker.client.LogMessage;
import org.eclipse.che.plugin.docker.client.ProgressLineFormatterImpl;
import org.eclipse.che.plugin.docker.client.json.ContainerInfo;
import org.eclipse.che.plugin.docker.machine.node.DockerNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.String.format;

/**
 * Docker implementation of {@link Instance}
 *
 * @author andrew00x
 * @author Alexander Garagatyi
 * @author Anton Korneta
 */
public class DockerInstance extends AbstractInstance {
    private static final Logger LOG = LoggerFactory.getLogger(DockerInstance.class);

    private static final AtomicInteger pidSequence           = new AtomicInteger(1);
    private static final String        PID_FILE_TEMPLATE     = "/tmp/docker-exec-%s.pid";
    private static final Pattern       PID_FILE_PATH_PATTERN = Pattern.compile(String.format(PID_FILE_TEMPLATE, "([0-9]+)"));
    /**
     * Produces output in form:
     * <pre>
     * /some/path/pid_file_template-1.pid
     * /some/path/pid_file_template-3.pid
     * /some/path/pid_file_template-14.pid
     * </pre>
     * Where each line is full path to pid file of <b>process that is running<b/>
     */
    private static final String GET_ALIVE_PROCESSES_COMMAND =
            format("for pidFile in $(find %s -print 2>/dev/null); do kill -0 \"$(cat ${pidFile})\" 2>/dev/null && echo \"${pidFile}\"; done",
                   format(PID_FILE_TEMPLATE, "*"));

    private final DockerMachineFactory                        dockerMachineFactory;
    private final String                                      container;
    private final DockerConnector                             docker;
    private final String                                      image;
    private final LineConsumer                                outputConsumer;
    private final String                                      registry;
    private final DockerNode                                  node;
    private final DockerInstanceStopDetector                  dockerInstanceStopDetector;
    private final DockerInstanceProcessesCleaner              processesCleaner;
    private final ConcurrentHashMap<Integer, InstanceProcess> machineProcesses;

    private MachineRuntimeInfoImpl machineRuntime;

    @Inject
    public DockerInstance(DockerConnector docker,
                          @Named("machine.docker.registry") String registry,
                          DockerMachineFactory dockerMachineFactory,
                          @Assisted Machine machine,
                          @Assisted("container") String container,
                          @Assisted("image") String image,
                          @Assisted DockerNode node,
                          @Assisted LineConsumer outputConsumer,
                          DockerInstanceStopDetector dockerInstanceStopDetector,
                          DockerInstanceProcessesCleaner processesCleaner) {
        super(machine);
        this.dockerMachineFactory = dockerMachineFactory;
        this.container = container;
        this.docker = docker;
        this.image = image;
        this.outputConsumer = outputConsumer;
        this.registry = registry;
        this.node = node;
        this.dockerInstanceStopDetector = dockerInstanceStopDetector;
        this.processesCleaner = processesCleaner;
        this.machineProcesses = new ConcurrentHashMap<>();
        processesCleaner.trackProcesses(this);
    }

    @Override
    public LineConsumer getLogger() {
        return outputConsumer;
    }

    @Override
    public MachineRuntimeInfoImpl getRuntime() {
        // if runtime info is not evaluated yet
        if (machineRuntime == null) {
            try {
                final ContainerInfo containerInfo = docker.inspectContainer(container);
                machineRuntime = new MachineRuntimeInfoImpl(dockerMachineFactory.createMetadata(containerInfo,
                                                                                                node.getHost(),
                                                                                                getConfig()));
            } catch (IOException e) {
                LOG.error(e.getLocalizedMessage(), e);
                return null;
            }
        }
        return machineRuntime;
    }

    @Override
    public InstanceProcess getProcess(final int pid) throws NotFoundException, MachineException {
        final InstanceProcess machineProcess = machineProcesses.get(pid);
        if (machineProcess != null) {
            try {
                machineProcess.checkAlive();
                return machineProcess;
            } catch (NotFoundException e) {
                machineProcesses.remove(pid);
                throw e;
            }
        }
        throw new NotFoundException(format("Process with pid %s not found", pid));
    }

    @Override
    public List<InstanceProcess> getProcesses() throws MachineException {
        List<InstanceProcess> processes = new LinkedList<>();
        try {
            final Exec exec = docker.createExec(container, false, "/bin/bash", "-c", GET_ALIVE_PROCESSES_COMMAND);
            docker.startExec(exec.getId(), logMessage -> {
                final String pidFilePath = logMessage.getContent().trim();
                final Matcher matcher = PID_FILE_PATH_PATTERN.matcher(pidFilePath);
                if (matcher.matches()) {
                    final int virtualPid = Integer.parseInt(matcher.group(1));
                    final InstanceProcess dockerProcess = machineProcesses.get(virtualPid);
                    if (dockerProcess != null) {
                        processes.add(dockerProcess);
                    } else {
                        LOG.warn("Machine process {} exists in container but missing in processes map", virtualPid);
                    }
                }
            });
            return processes;
        } catch (IOException e) {
            throw new MachineException(e);
        }
    }

    @Override
    public InstanceProcess createProcess(Command command, String outputChannel) throws MachineException {
        final Integer pid = pidSequence.getAndIncrement();
        final InstanceProcess process = dockerMachineFactory.createProcess(command,
                                                                           container,
                                                                           outputChannel,
                                                                           String.format(PID_FILE_TEMPLATE, pid),
                                                                           pid);
        machineProcesses.put(pid, process);
        return process;
    }

    @Override
    public InstanceKey saveToSnapshot(String owner) throws MachineException {
        try {
            final String repository = generateRepository();
            String comment = format("Suspended at %1$ta %1$tb %1$td %1$tT %1$tZ %1$tY", System.currentTimeMillis());
            if (owner != null) {
                comment = comment + " by " + owner;
            }
            // !! We SHOULD NOT pause container before commit because all execs will fail
            // to push image to private registry it should be tagged with registry in repo name
            // https://docs.docker.com/reference/api/docker_remote_api_v1.16/#push-an-image-on-the-registry
            docker.commit(container, registry + "/" + repository, null, comment, owner);
            //TODO fix this workaround. Docker image is not visible after commit when using swarm
            Thread.sleep(2000);

            final ProgressLineFormatterImpl progressLineFormatter = new ProgressLineFormatterImpl();
            docker.push(repository, null, registry, currentProgressStatus -> {
                try {
                    outputConsumer.writeLine(progressLineFormatter.format(currentProgressStatus));
                } catch (IOException ignored) {
                }
            });
            return new DockerInstanceKey(repository, registry);
        } catch (IOException e) {
            throw new MachineException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MachineException(e.getLocalizedMessage(), e);
        }
    }

    private String generateRepository() {
        return NameGenerator.generate(null, 16);
    }

    @Override
    public void destroy() throws MachineException {
        machineProcesses.clear();
        processesCleaner.untrackProcesses(getId());
        dockerInstanceStopDetector.stopDetection(container);
        try {
            if (getConfig().isDev()) {
                node.unbindWorkspace();
            }

            docker.killContainer(container);

            docker.removeContainer(container, true, true);
        } catch (IOException e) {
            throw new MachineException(e.getLocalizedMessage());
        }

        try {
            docker.removeImage(image, false);
        } catch (IOException ignore) {
        }
    }

    @Override
    public DockerNode getNode() {
        return node;
    }

    /**
     * Reads file content by specified file path.
     *
     * TODO:
     * add file size checking,
     * note that size checking and file content reading
     * should be done in an atomic way,
     * which means that two separate instance processes is not the case.
     *
     * @param filePath
     *         path to file on machine instance
     * @param startFrom
     *         line number to start reading from
     * @param limit
     *         limitation on line
     * @return if {@code limit} and {@code startFrom} grater than 0
     * content from {@code startFrom} to {@code startFrom + limit} will be returned,
     * if file contains less lines than {@code startFrom} empty content will be returned
     * @throws MachineException
     *         if any error occurs with file reading
     */
    @Override
    public String readFileContent(String filePath, int startFrom, int limit) throws MachineException {
        if (limit <= 0 || startFrom <= 0) {
            throw new MachineException("Impossible to read file " + limit + " lines from " + startFrom + " line");
        }

        // command sed getting file content from startFrom line to (startFrom + limit)
        String bashCommand = format("sed -n \'%1$2s, %2$2sp\' %3$2s", startFrom, startFrom + limit, filePath);

        final String[] command = {"/bin/bash", "-c", bashCommand};

        ListLineConsumer lines = new ListLineConsumer();
        try {
            Exec exec = docker.createExec(container, false, command);
            docker.startExec(exec.getId(), new LogMessagePrinter(lines, LogMessage::getContent));
        } catch (IOException e) {
            throw new MachineException(format("Error occurs while initializing command %s in docker container %s: %s",
                                              Arrays.toString(command), container, e.getLocalizedMessage()), e);
        }

        String content = lines.getText();
        if (content.contains("sed: can't read " + filePath + ": No such file or directory") ||
            content.contains("cat: " + filePath + ": No such file or directory")) {
            throw new MachineException("File with path " + filePath + " not found");
        }
        return content;
    }

    /**
     * Copies files from specified container.
     *
     * @param sourceMachine
     *         source machine
     * @param sourcePath
     *         path to file or directory inside specified container
     * @param targetPath
     *         path to destination file or directory inside container
     * @param overwrite
     *         If "false" then it will be an error if unpacking the given content would cause
     *         an existing directory to be replaced with a non-directory and vice versa.
     * @throws MachineException
     *         if any error occurs when files are being copied
     */
    @Override
    public void copy(Instance sourceMachine, String sourcePath, String targetPath, boolean overwrite) throws MachineException {
        if (!(sourceMachine instanceof DockerInstance)) {
            throw new MachineException("Unsupported copying between not docker machines");
        }
        try {
            docker.putResource(container,
                               targetPath,
                               docker.getResource(((DockerInstance)sourceMachine).container, sourcePath),
                               overwrite);
        } catch (IOException e) {
            throw new MachineException(e.getLocalizedMessage());
        }
    }

    /**
     * Removes process from the list of processes
     *
     * <p>Used by {@link DockerInstanceProcessesCleaner}
     */
    void removeProcess(int pid) {
        machineProcesses.remove(pid);
    }
}
