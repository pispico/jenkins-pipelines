#!/usr/bin/groovy

import br.com.jenkins.Constants

def call(body){
	def config = [:]
	body.resolveStrategy = Closure.DELEGATE_FIRST
	body.delegate = config
	body()

	def utilities = new br.com.jenkins.Utilities();

	def NAMESPACE = ''
	def VERSION = ''
	def SONAR_CE_TASK_URL = ''
	def BUILDER_USER_NAME = ''

  def NAMESPACE_PREFIX = config.projectPrefixNamespace
	def PROJECT_NAME = config.projectName
	def PROJECT_REPO = config.projectRepo

	pipeline {
		agent {
    	node {
				label 'jenkinsMavenSlave'
			}
  	}

		stages{
			stage('Define Type'){
				steps {
					script {
						BUILDER_USER_NAME = wrap([$class: 'BuildUser']) {
							return env.BUILD_USER
						}

						if ((env.BRANCH_NAME ==~ /^release.*/ || env.BRANCH_NAME ==~ /^hotfix.*/) && !env.CHANGE_ID) {
							if(env.BRANCH_NAME.contains("release")){
								VERSION = env.BRANCH_NAME.replace('release-', '').trim();
								NAMESPACE = "${NAMESPACE_PREFIX}-sit"
							}else{
								VERSION = env.BRANCH_NAME.replace('hotfix-', '').trim();
								NAMESPACE = "${NAMESPACE_PREFIX}-stg"
							}
							VERSION = VERSION + '.' + env.BUILD_ID;
						}
					}
				}
			}

			stage('Init'){
				steps {
					script {
						if ((env.BRANCH_NAME ==~ /^release.*/ || env.BRANCH_NAME ==~ /^hotfix.*/) && !env.CHANGE_ID) {
							withMaven(maven: 'M3', mavenSettingsConfig: 'maven-settings') {
								sh "mvn versions:set -DnewVersion=${VERSION} -DgenerateBackupPoms=false"
							}
						}
					}
				}
			}

			stage('Unit Test') {
				steps {
					withMaven(maven: 'M3', mavenSettingsConfig: 'maven-settings') {
                      	sh 'mvn clean install -U'
						sh 'mvn clean test'
					}
				}
			}

			stage('Sonar') {
				steps {
					script {
						if ((env.BRANCH_NAME ==~ /^release.*/ || env.BRANCH_NAME ==~ /^hotfix.*/) && !env.CHANGE_ID) {
							withMaven(maven: 'M3', mavenSettingsConfig: 'maven-settings') {
								sh 'mvn clean package sonar:sonar -Dspring.profiles.active=docker'
								script {
									def props = readProperties file: 'target/sonar/report-task.txt'
									SONAR_CE_TASK_URL = props.ceTaskUrl
									echo SONAR_CE_TASK_URL
								}
							}
						}
					}
				}
			}

			stage('Quality Gate') {
				steps {
					script {
						if ((env.BRANCH_NAME ==~ /^release.*/ || env.BRANCH_NAME ==~ /^hotfix.*/) && !env.CHANGE_ID) {
							timeout(time: 5, unit: 'MINUTES') {
								def ceTask
								waitUntil {
		        			sh "curl ${SONAR_CE_TASK_URL} -o ceTask.json"
		        			ceTask = readJSON file: 'ceTask.json'
		        			echo ceTask.toString()
		        			return "SUCCESS".equals(ceTask.task.status)
		      			}
		      			def qualityGateUrl = "http://sonar.viavarejo.com.br/api/qualitygates/project_status?analysisId=" + ceTask.task.analysisId
		      			sh "curl $qualityGateUrl -o qualityGate.json"
		      			def qualityGate = readJSON file: 'qualityGate.json'
		      			echo qualityGate.toString()
		      			if ("ERROR".equals(qualityGate.projectStatus.status)) {
		      				// error  "Quality Gate failure"
		      				echo '<<< "Quality Gate failure" >>>'
		      			}
							}
						}
					}
				}
			}

			stage('Maven Build') {
				steps {
					withMaven(maven: 'M3', mavenSettingsConfig: 'maven-settings') {
						sh 'mvn clean package -DskipTests'
					}
				}
			}

			stage('Git Version') {
				steps {
					script {
						if ((env.BRANCH_NAME ==~ /^release.*/ || env.BRANCH_NAME ==~ /^hotfix.*/) && !env.CHANGE_ID) {
							withCredentials([usernamePassword(credentialsId: 'bitbucket-prod', passwordVariable: 'GIT_PASSWORD', usernameVariable: 'GIT_USERNAME')]) {
								sh "git config --global user.email 'alm.jenkins@viavarejo.com.br'"
								sh "git config --global user.name 'Jenkins'"

								sh "git add -A"
								sh "git commit --no-verify -m '${VERSION}'"
								sh "git tag -a ${VERSION} -f -m '${VERSION}'"
								sh "git push http://${GIT_USERNAME}:${GIT_PASSWORD}@${PROJECT_REPO} --tags"
								sh "git push http://${GIT_USERNAME}:${GIT_PASSWORD}@${PROJECT_REPO} HEAD:${env.BRANCH_NAME}"
							}
						}
					}
				}
			}

			stage('Nexus') {
				steps {
					script {
						if ((env.BRANCH_NAME ==~ /^release.*/ || env.BRANCH_NAME ==~ /^hotfix.*/) && !env.CHANGE_ID) {
							withMaven(maven: 'M3', mavenSettingsConfig: 'maven-settings') {
								sh 'mvn deploy -DskipTests'
							}
						}
					}
				}
			}

			stage('Create Image Builder') {
				steps {
					script {
						if ((env.BRANCH_NAME ==~ /^release.*/ || env.BRANCH_NAME ==~ /^hotfix.*/) && !env.CHANGE_ID) {
							openshift.withCluster() {
								openshift.withProject("${NAMESPACE}") {
									echo "Using project: ${openshift.project()}"
									if (!openshift.selector("buildconfig", "${PROJECT_NAME}").exists()) {
										openshift.newBuild("--name=${PROJECT_NAME}", "--image-stream=fis-java-openshift:2.0", "--binary")
									}
								}
							}
						}
					}
				}
			}

			stage('Build Image') {
				steps {
					script {
						if ((env.BRANCH_NAME ==~ /^release.*/ || env.BRANCH_NAME ==~ /^hotfix.*/) && !env.CHANGE_ID) {
							openshift.withCluster() {
								openshift.withProject("${NAMESPACE}") {
                                  	echo "Project name ${PROJECT_NAME}"
									openshift.selector("buildconfig", "${PROJECT_NAME}").startBuild("--from-file=target/${PROJECT_NAME}.jar", "--wait")
								}
							}
						}
					}
				}
			}

			stage('Push Image') {
				steps {
					script {
						if ((env.BRANCH_NAME ==~ /^pirombeta.*/ || env.BRANCH_NAME ==~ /^hotfix.*/) && !env.CHANGE_ID) {
							openshift.withCluster() {
								openshift.withProject("${NAMESPACE}") {
									openshift.tag("${PROJECT_NAME}:latest", "${PROJECT_NAME}:${VERSION}")
								}
							}
						}
					}
				}
			}

			stage('Continuous Deployment') {
				steps {
					script {
						if ((env.BRANCH_NAME ==~ /^release.*/ || env.BRANCH_NAME ==~ /^hotfix.*/) && !env.CHANGE_ID) {
							openshift.withCluster() {
								openshift.withProject("${NAMESPACE}") {
									openshift.set('image',"deploymentconfig/${PROJECT_NAME}", "${PROJECT_NAME}=${Constants.OPSHFT_REGISTRY}/${NAMESPACE}/${PROJECT_NAME}:${VERSION}")
								}
							}
						}
					}
				}
			}


		}

		post {
			success {
				office365ConnectorSend message: "Sucesso", status:"Build com sucesso", webhookUrl:"${Constants.TEAMS}"
				script {
					utilities.sendSuccessfulDeploySlackMessage(NAMESPACE, "${VERSION}", PROJECT_NAME, BUILDER_USER_NAME);
				}
			}
			failure {
				office365ConnectorSend message: "Falha", status:"Build com falha", webhookUrl:"${Constants.TEAMS}"
				script {
					utilities.sendUnsuccessfulDeploySlackMessage(NAMESPACE, "${VERSION}", PROJECT_NAME, BUILDER_USER_NAME);
				}
			}
		}

	}
}
