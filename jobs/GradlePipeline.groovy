def dockerImgName = "alxl/${projectName}"
def dockerTLatest = "${dockerImgName}:latest"
def dockerTBuildNum = "${dockerImgName}:${currentBuild.number}"

def projMsgName = "project '${projectName}' [${gitBranch}:${currentBuild.number}]"

pipeline {
    agent any

    tools {
        gradle 'gradle'
    }

    stages {
        stage('Pull') {
            steps {
                git url: "${gitURL}", branch: "${gitBranch}"
            }
        }

        stage('Build & Test') {
            steps {
                sh 'chmod +x gradlew && ./gradlew build'
            }
            post {
                failure {
                    slackSend(color: 'danger', channel: "${slackChannel}",
                    message: "${projMsgName} failed to build.")
                }
            }
        }

        stage('Sonar') {
            steps {
                withSonarQubeEnv('sonar-server') {
                    sh './gradlew sonarqube'
                }
            }
            post {
                failure {
                    slackSend(color: 'danger', channel: "${slackChannel}",
                    message: "${projMsgName} failed to get scanned by SonarCloud.")
                }
            }
        }

        stage('Quality Gate') {
            steps {
                timeout(time: 5, unit: 'MINUTES') {
                    waitForQualityGate abortPipeline: true
                }
            }
        }

        stage('Make Docker image') {
            steps {
                sh "docker build -t ${dockerTBuildNum} ."
            }
            post {
                failure {
                    slackSend(color: 'danger', channel: "${slackChannel}",
                    message: "${projMsgName} failed to Dockerize.")
                }
            }
        }

        stage('Scan image') {
            steps {
                catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                    sh "trivy image --exit-code 1 ${dockerTBuildNum}"
                }
            }
            post {
                failure {
                    slackSend(color: 'warning', channel: "${slackChannel}",
                    message: "${projMsgName} has vulnerabilities in its image.")
                }
            }
        }

        stage('Push to Docker Hub') {
            steps {
                sh "docker tag ${dockerTBuildNum} ${dockerTLatest}"
                withDockerRegistry([credentialsId: 'dockerhub-creds', url: '']) {
                    sh "docker push ${dockerTBuildNum}"
                    sh "docker push ${dockerTLatest}"
                }
            }
            post {
                failure {
                    slackSend(color: 'danger', channel: "${slackChannel}",
                    message: "${projMsgName} failed to push to the CR.")
                }
            }
        }

        stage('Deploy to Kubernetes') {
            steps {
                withKubeConfig([credentialsId: 'kubectl-creds', serverUrl: "${kubectlServer}"]) {
                    sh 'kubectl apply -f kube/ -n revcog-test'
                    sh 'kubectl apply -f kube/ -n revcog-prod'
                    sh "kubectl rollout restart deployment/${projectName} -n revcog-test"
                    sh "kubectl rollout restart deployment/${projectName} -n revcog-prod"
                }
            }
            post {
                failure {
                    slackSend(color: 'danger', channel: "${slackChannel}",
                    message: "${projMsgName} failed to deploy to K8s.")
                }
            }
        }
    }
    post {
        success {
            slackSend(color: 'good', channel: "${slackChannel}",
                message: "${projMsgName} passed all tests and was successfully built and pushed to the CR.")
        }
    }
}

