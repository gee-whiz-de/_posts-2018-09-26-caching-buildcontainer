FROM hub.gee-whiz.de/build-env:latest

ONBUILD ARG JDK_HOME
ONBUILD ARG MAVEN_HOME
ONBUILD ARG NODEJS_HOME
ONBUILD ARG PROJECT
ONBUILD ARG IGNORE_REPOS=""
ONBUILD ARG BITBUCKET_HTTP_CREDENTIALS

ONBUILD COPY npm-global-rc maven-global-settings.xml id_rsa /tmp/build/

ONBUILD RUN \
    # Verify presence of build-args
        [ -n "${JDK_HOME}" ] || { echo "error: build-arg JDK_HOME is not set"; exit 1; } \
        && [ -n "${MAVEN_HOME}" ] || { echo "error: build-arg MAVEN_HOME is not set"; exit 1; } \
        && [ -n "${NODEJS_HOME}" ] || { echo "error: build-arg NODEJS_HOME is not set"; exit 1; } \
        && [ -n "${PROJECT}" ] || { echo "error: build-arg PROJECT is not set"; exit 1; } \
        && [ -n "${BITBUCKET_HTTP_CREDENTIALS}" ] || { echo "error: build-arg BITBUCKET_HTTP_CREDENTIALS is not set"; exit 1; } \
    # Print some information
        && echo -n "Build buildcontainer for ${PROJECT}" \
        && [ -n "${IGNORE_REPOS}" ] && echo " while ignoring the following repositories: ${IGNORE_REPOS}" \
        || echo " with all repositories " \
    # Retrieve available repositories and store them as line-separated JSON objects { "name": "$name", "url": "$url" }
        && FILTER="$(echo "(\"${IGNORE_REPOS}\")" | sed 's/,/"|"/g')" \
        && REPOS=$(curl -S -u ${BITBUCKET_HTTP_CREDENTIALS} http://git.gee-whiz.de/rest/api/1.0/projects/${PROJECT}/repos?limit=100 \
            | jq -cM '.values[] | {name: .name, url: .links.clone[].href} | select(.url | contains("ssh://"))' \
            | grep -Ev "${FILTER}") \
        && echo -n "Considering $(echo ${REPOS} | jq -cMs '. | length') repositories for buildcontainer creation process: " \
        && echo "$(echo ${REPOS} | jq -cM '.name ' | jq -cMs '.')" \
    # Use provided SSH key for Bitbucket authentication
        && mkdir -p ~root/.ssh \
        && echo -e "Host git.gee-whiz.de\nIdentityFile /tmp/build/id_rsa\nIdentitiesOnly yes" > ~root/.ssh/config \
        && cp ~jenkins/.ssh/known_hosts ~root/.ssh/known_hosts \
    # Create git reference repo
        && cd /var/tmp/cache/git \
        && git init --bare \
        && for REPO in ${REPOS}; do \
            REPO_NAME=$(echo ${REPO} | jq -cMr '.name') \
            && REPO_URL=$(echo ${REPO} | jq -cMr '.url') \
            && git remote add ${REPO_NAME} ${REPO_URL} \
            ; done \
        && git fetch --all --quiet \
        && cd / \
    # Clone repositories for dependency prefetching
        && mkdir -p /tmp/git \
        && for REPO in ${REPOS}; do \
            REPO_NAME=$(echo ${REPO} | jq -cMr '.name') \
            && REPO_URL=$(echo ${REPO} | jq -cMr '.url') \
            && git clone --reference /var/tmp/cache/git ${REPO_URL} /tmp/git/${REPO_NAME} \
            ; done \
        # Prefetch Maven dependencies
            && for REPO_DIR in /tmp/git/*; do \
                echo "Scanning for Maven project in ${REPO_DIR}" \
                && if [ -f ${REPO_DIR}/pom.xml ]; then \
                    echo "Found pom.xml in ${REPO_DIR}" \
                    && cd ${REPO_DIR} \
                    && JAVA_HOME=${JDK_HOME} ${MAVEN_HOME}/bin/mvn -gs /tmp/build/maven-global-settings.xml -B -V -q --fail-never org.apache.maven.plugins:maven-dependency-plugin:3.0.2:go-offline \
                    ; fi \
                ; done \
        # Prefetch NPM dependencies
            && PATH=${PATH}:${NODEJS_HOME}/bin \
            && for REPO_DIR in /tmp/git/*; do \
                echo "Scanning for NPM projects in ${REPO_DIR}/*" \
                && for NODE_PACKAGE in $(find ${REPO_DIR} -maxdepth 2 -iname package.json); do \
                    NODE_PACKAGE_DIR=$(dirname ${NODE_PACKAGE}) \
                    && echo "Found package.json in ${NODE_PACKAGE_DIR}" \
                    && cd ${NODE_PACKAGE_DIR} \
                    && NODE_TLS_REJECT_UNAUTHORIZED=0 npm --globalconfig /tmp/build/npm-global-rc install \
                    ; done \
                ; done \
            && cd / \
    # Setup permissions
        && chown -R jenkins:jenkins /var/tmp/cache \
    # Cleanup
        && rm -rf ~/.ssh/config /tmp/build/id_rsa /tmp/git