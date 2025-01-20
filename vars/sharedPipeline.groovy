def call(Map config = [:]) {
    pipeline {
        agent {
            kubernetes {
                inheritFrom config.get('DOCKER_AGENT', 'docker-agent')
            }
        }

        environment {
            SLACK_CHANNEL = config.get('SLACK_CHANNEL', '#ci-cd')
            DOCKERHUB_REPO = config.get('DOCKERHUB_REPO', 'default/repo')
            IMAGE_TAG = config.get('IMAGE_TAG', 'v1.0.')
            ENV_FILE_NAME = config.get('ENV_FILE_NAME', 'default.env')
            PROJECT_KEY = config.get('PROJECT_KEY', 'default-project-key')
            PROJECT_NAME = config.get('PROJECT_NAME', 'default-project-name')
            SONAR_HOST_URL = config.get('SONAR_HOST_URL', 'http://sonarqube.example.com/')
            EXCLUSIONS = config.get('EXCLUSIONS', '**/ci-cd/**,**/tests/**')
            TESTS = config.get('TESTS', 'tests')
            DEPLOYMENT_NAME = config.get('DEPLOYMENT_NAME', 'default-deployment')
            NAMESPACE = config.get('NAMESPACE', 'default')
            TIMEOUT = config.get('TIMEOUT', '60s')
            DOCKER_AGENT = config.get('DOCKER_AGENT', 'docker-agent')
            DOCKER_CREDENTIALS_ID = config.get('DOCKER_CREDENTIALS_ID', 'default-docker-credentials')
        }

        stages {
            stage('Start Pipeline') {
                steps {
                    script {
                        def slackMessage = slackSend(channel: SLACK_CHANNEL, color: '#808080', message: "Pipeline started for ${env.JOB_NAME} - Build #${env.BUILD_NUMBER}")
                        def messageTs = slackMessage.messageId // Capture the message timestamp for updates

                        // Add an initial reaction with stage info
                        slackAddReaction(messageTs, '🔄', 'Pipeline Start')
                    }
                }
            }

            stage('INJECTING ENV FILES') {
                steps {
                    script {
                        slackUpdateMessage(messageTs, "Injecting environment files...")
                        container(DOCKER_AGENT) {
                            withCredentials([usernamePassword(credentialsId: "devops-github-token", usernameVariable: 'GIT_USERNAME', passwordVariable: 'GIT_PASSWORD')]) {
                                sh """
                                curl -u "${GIT_USERNAME}:${GIT_PASSWORD}" -LJO https://raw.githubusercontent.com/lunarone-blackrock/configs/main/${ENV_FILE_NAME}
                                mv ${ENV_FILE_NAME} .env
                                cat .env
                                """
                            }
                        }
                        slackUpdateMessage(messageTs, "✅ Environment files injected.")
                        slackAddReaction(messageTs, '🔧', 'Injecting Environment Files')
                    }
                }
            }

            stage('Run Unit Tests') {
                steps {
                    script {
                        slackUpdateMessage(messageTs, "Running unit tests...")

                        // Check if the 'tests/' directory exists
                        def testsExist = fileExists('tests')

                        if (testsExist) {
                            slackSend(channel: SLACK_CHANNEL, color: '#808080', message: "Running unit tests on ${DEPLOYMENT_NAME}")
                            
                            try {
                                container(DOCKER_AGENT) {
                                    sh '''
                                        # Set up virtual environment
                                        python3 -m venv venv
                                        . venv/bin/activate

                                        # Install dependencies
                                        pip install -r requirements.txt

                                        # Run tests
                                        pytest tests/ --junitxml=reports/test-results.xml
                                        
                                        # Clean up virtual environment
                                        deactivate
                                    '''
                                }
                                junit 'reports/test-results.xml' // Publish test results
                                slackSend(channel: SLACK_CHANNEL, color: '#00FF00', message: "All unit tests completed successfully on ${DEPLOYMENT_NAME}")
                            } catch (Exception e) {
                                slackSend(channel: SLACK_CHANNEL, color: '#FF0000', message: "Unit tests failed for ${DEPLOYMENT_NAME}. Aborting.")
                                junit 'reports/test-results.xml' // Publish partial results if available
                                error("Unit tests failed.") // Stop the pipeline
                            }
                        } else {
                            slackUpdateMessage(messageTs, "✅ Unit tests completed.")
                            slackAddReaction(messageTs, '🧪', 'Unit Tests Completed')
                        }
                    }
                }
            }

            stage('SonarQube Analysis') {
                steps {
                    script {
                        slackUpdateMessage(messageTs, "Running SonarQube analysis...")
                        def scannerHome = tool 'SonarScanner';
                        withSonarQubeEnv() {
                            sh "${scannerHome}/bin/sonar-scanner \
                                -Dsonar.projectKey=${PROJECT_KEY} \
                                -Dsonar.projectName=${PROJECT_NAME}\
                                -Dsonar.sources=. \
                                -Dsonar.host.url=${SONAR_HOST_URL} \
                                -Dsonar.ce.javaOpts=-Xmx512m \
                                -Dsonar.ws.timeout=600 \
                                -Dsonar.tests=${TESTS} \
                                -Dsonar.exclusions=${EXCLUSIONS} || true"
                        }
                        slackUpdateMessage(messageTs, "✅ SonarQube analysis completed.")
                        slackAddReaction(messageTs, '🔍', 'SonarQube Analysis Completed')
                    }
                }
            }

            stage('Building & Pushing Docker Image') {
                steps {
                    script {
                        slackUpdateMessage(messageTs, "Building Docker image...")
                        container(DOCKER_AGENT) {
                            withCredentials([usernamePassword(credentialsId: DOCKER_CREDENTIALS_ID, usernameVariable: 'DOCKERHUB_USERNAME', passwordVariable: 'DOCKERHUB_PASSWORD')]) {
                                sh """
                                docker login --username \${DOCKERHUB_USERNAME} --password \${DOCKERHUB_PASSWORD}
                                docker build -t \${DOCKERHUB_REPO}:${IMAGE_TAG}${BUILD_NUMBER} .
                                docker push \${DOCKERHUB_REPO}:${IMAGE_TAG}${BUILD_NUMBER}
                                docker tag \${DOCKERHUB_REPO}:${IMAGE_TAG}${BUILD_NUMBER} \${DOCKERHUB_REPO}:latest
                                docker push \${DOCKERHUB_REPO}:latest
                                """
                            }
                        }
                        slackUpdateMessage(messageTs, "✅ Docker image built and pushed.")
                        slackAddReaction(messageTs, '🐳', 'Docker Build & Push Completed')
                    }
                }
            }

            stage('Deploying Image') {
                steps {
                    script {
                        slackUpdateMessage(messageTs, "Deploying Docker image...")
                        container(DOCKER_AGENT) {
                            sh """
                            kubectl set image deployment/${DEPLOYMENT_NAME} ${DEPLOYMENT_NAME}=\${DOCKERHUB_REPO}:${IMAGE_TAG}${BUILD_NUMBER} -n ${NAMESPACE}
                            kubectl rollout status deployment/${DEPLOYMENT_NAME} -n ${NAMESPACE} --timeout=${TIMEOUT}
                            """
                        }
                        slackUpdateMessage(messageTs, "✅ Docker image deployed.")
                        slackAddReaction(messageTs, '🚀', 'Docker Image Deployed')
                    }
                }
            }
        }

        post {
            always {
                script {
                    def color = currentBuild.result == 'SUCCESS' ? '#00FF00' : '#FF0000'
                    slackUpdateMessage(messageTs, "${currentBuild.result == 'SUCCESS' ? '✅ Pipeline Succeeded!' : '❌ Pipeline Failed!'}\nBuild URL: ${env.BUILD_URL}")
                    slackAddReaction(messageTs, currentBuild.result == 'SUCCESS' ? '✅' : '❌', 'Pipeline Ended')
                }
            }
        }
    }
}

def slackUpdateMessage(messageTs, text) {
    slackSend(channel: SLACK_CHANNEL, ts: messageTs, text: text)
}

def slackAddReaction(messageTs, emoji, description) {
    // Use Slack API to add a reaction with description for each stage
    slackSend(channel: SLACK_CHANNEL, ts: messageTs, reaction: emoji)
    slackSend(channel: SLACK_CHANNEL, ts: messageTs, text: ":information_source: *${description}*")
}
