@Library('jenkins-shared-library') _
sharedPipeline([
    SLACK_CHANNEL: '#ci-cd',
    DOCKERHUB_REPO: 'blackrocktech/api-dev-k8s',
    IMAGE_TAG: 'v1.0.',
    ENV_FILE_NAME: 'api-dev-k8s.env',
    PROJECT_KEY: 'api-dev-k8s',
    PROJECT_NAME: 'api-dev-k8s',
    SONAR_HOST_URL: 'http://sonarqube.live.blackrockapi.com/',
    EXCLUSIONS: '**/ci-cd/**,**/tests/**',
    TESTS: 'tests',
    DEPLOYMENT_NAME: 'api-dev-k8s',
    NAMESPACE: 'dev',
    TIMEOUT: '60s',
    DOCKER_AGENT: 'docker-agent',
    DOCKER_CREDENTIALS_ID: 'DockerHub-Creds'
])
