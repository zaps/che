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
package org.eclipse.che.api.git;

import org.eclipse.che.api.project.server.type.TransientMixin;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * @author Vitaly Parfonov
 */
@Singleton
public class GitProjectType extends TransientMixin {

    public static final String VCS_PROVIDER_NAME       = "vcs.provider.name";
    public static final String GIT_CURRENT_BRANCH_NAME = "git.current.branch.name";
    public static final String GIT_REPOSITORY_REMOTES  = "git.repository.remotes";

    @Inject
    public GitProjectType(GitValueProviderFactory gitRepositoryValueProviderFactory) {
        super("git", "git");
        addVariableDefinition(VCS_PROVIDER_NAME, "Is this git repo or not?", false,
                              gitRepositoryValueProviderFactory);
        addVariableDefinition(GIT_CURRENT_BRANCH_NAME, "Name of current git branch", false,
                              gitRepositoryValueProviderFactory);
        addVariableDefinition(GIT_REPOSITORY_REMOTES, "List of git repository remote addresses", false,
                              gitRepositoryValueProviderFactory);
    }
}
