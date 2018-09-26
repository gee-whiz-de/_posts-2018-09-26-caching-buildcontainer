#!/usr/bin/env groovy

def createConfig(Closure body) {
    def config = [
        project: null,
        triggeredBy: null,
        ignoreRepos: [],
        timeoutAfterHours: 1,
        dockerRegistry: 'hub.gee-whiz.de',
        dockerRegistryCredentialsID: 'e9b73264-635d-4efc-95a0-6f6d313aba6a',
        failureMailRecipient: null,
        agent: 'build-env-docker',
        bitbucketSshKeyId: '64096f81-1654-4652-b8aa-88a05a621e1e',
        bitbucketHttpCredentialsId: 'cd89e525-d741-4efd-8792-87830b6ca439'
    ]

    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    config.each{ k, v -> echo "Setting ${k} to ${v}" }

    if (!config.project) {
        error 'You must set a project'
    }

    return config
}

def call(Closure body) {
    def config = createConfig(body)

    pipeline {
        agent { label config.agent }
    
        triggers {
            upstream(upstreamProjects: config.triggeredBy, threshold: hudson.model.Result.SUCCESS)
        }

        options {
            ansiColor('xterm')
            timestamps()
            buildDiscarder(logRotator(numToKeepStr: '5'))
            timeout(time: config.timeoutAfterHours, unit: 'HOURS')
        }
    
        environment {
            MAVEN_GLOBAL_SETTINGS_ID = '4d7f5293-c7f1-4ef6-9d4a-d20fa862bc05'
            NPM_GLOBAL_RC_ID = 'b4cd8372-9e2a-4dd9-b5be-73c15a5ed56c'
            IMAGE_NAME = "hub.gee-whiz.de/build-env-${config.project}"
        }

        stages {

            stage('Provision configuration files') {
                steps {
                    configFileProvider([
                                configFile(fileId: "${MAVEN_GLOBAL_SETTINGS_ID}", variable: 'MAVEN_GLOBAL_SETTINGS'),
                                configFile(fileId: "${NPM_GLOBAL_RC_ID}", variable: 'NPM_GLOBAL_RC')
                                ]) {
                        sh """
                            cp ${MAVEN_GLOBAL_SETTINGS} maven-global-settings.xml
                            cp ${NPM_GLOBAL_RC} npm-global-rc
                            chmod 644 npm-global-rc maven-global-settings.xml
                        """
                    }
                }
            }

            stage('Provision secrets') {
                steps {
                    withCredentials([sshUserPrivateKey(credentialsId: config.bitbucketSshKeyId, keyFileVariable: 'BITBUCKET_SSH_KEY_PATH')]) {
                        sh """
                            cp ${BITBUCKET_SSH_KEY_PATH} id_rsa
                            chmod 400 id_rsa
                        """
                    }
                }
            }

            stage("Build and push project specific Docker image") {
                environment {
                    JDK_HOME = "${tool type: 'jdk', name: 'JDK8'}"
                    MAVEN_HOME = "${tool type: 'maven', name: '3.5.4'}"
                    NODEJS_HOME = "${tool type: 'com.cloudbees.jenkins.plugins.customtools.CustomTool', name: 'Node.js 8.11.3'}"
                }

                steps {
                    withCredentials([usernameColonPassword(credentialsId:  config.bitbucketHttpCredentialsId, variable: 'BITBUCKET_HTTP_CREDENTIALS')]) {
                        withEnv(["PATH+DOCKER=${tool type: 'org.jenkinsci.plugins.docker.commons.tools.DockerTool', name: '17.09.1-ce'}/bin"]) {
                            withDockerRegistry([credentialsId: config.dockerRegistryCredentialsID, url: "https://${config.dockerRegistry}"]) {
                                sh """
                                    docker build \
                                        --no-cache \
                                        --tag ${IMAGE_NAME}:latest \
                                        --build-arg PROJECT=${config.project} \
                                        --build-arg IGNORE_REPOS=${config.ignoreRepos.join(',')} \
                                        --build-arg JDK_HOME=${JDK_HOME} \
                                        --build-arg MAVEN_HOME=${MAVEN_HOME} \
                                        --build-arg NODEJS_HOME=${NODEJS_HOME} \
                                        --build-arg BITBUCKET_HTTP_CREDENTIALS=${BITBUCKET_HTTP_CREDENTIALS} \
                                        .
                                """
                                sh 'docker push ${IMAGE_NAME}:latest'
                            }
                        }
                    }
                }
            }
        }
    }
}
