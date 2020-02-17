#!groovy
import groovy.json.*

def jsonParams = new JsonSlurper().parseText(JsonObject).build

pipeline {
	agent {
		label 'centos7'
	}
	options {
		buildDiscarder(logRotator(numToKeepStr: '50'))
		ansiColor('xterm')
		timestamps()
		skipDefaultCheckout()
	}
	parameters {
		string(name: 'JsonObject')
		string(name: 'branchOverride', defaultValue: '')
		string(name: 'eflowJobId', defaultValue: '')
		string(name: 'artifactPath', defaultValue: '')
		string(name: 'publishRepo')
		booleanParam(name: 'isReleaseBuild')
	}
	stages {
		stage('Load NPM Dependencies') {
			steps {
				nodejs(nodeJSInstallationName: jsonParams.nodejsTool, configId: 'npm_auth_config') {
					cleanWs()
					script {
						currentBuild.description = "Repo: ${jsonParams.appRepo}"
						// branchOverride has precedence over jsonParams.branch
						if (branchOverride != '') {
							jsonParams.branch = branchOverride
						}
						cleanCheckout jsonParams.branch, jsonParams.appRepo

						sh 'npm config ls'
						def myServer = Artifactory.server(jsonParams.artifactoryServer)
						def rtNpm = Artifactory.newNpmBuild()
						rtNpm.resolver server: myServer, repo: jsonParams['resolverReleaseRepo']
						rtNpm.deployer server: myServer, repo: jsonParams['deployerReleaseRepo']

						myBuildInfo = Artifactory.newBuildInfo()
						def packageJSON = readJSON file: 'package.json'
						def packageVersion = packageJSON.version

						if (eflowJobId != '') {
							echo "appVersion property: $packageVersion"
							httpRequest authentication: 'electricFlowApi', contentType: 'APPLICATION_JSON_UTF8', httpMode: 'PUT', requestBody: '{}', url: "https://${env.EflowServer}/rest/v1.0/properties/appVersion?jobId=${java.net.URLEncoder.encode(eflowJobId, 'UTF-8')}&value=${java.net.URLEncoder.encode(packageVersion, 'UTF-8')}"
						}
						try {
							rtNpm.install buildInfo: myBuildInfo
						} catch (Exception e) {
							throw e
						}
					}
				}
			}
		}
		stage ('Build Package') {
			steps {
				nodejs(nodeJSInstallationName: jsonParams.nodejsTool, configId: 'npm_auth_config') {
					script {
						echo "=============Building the Artifact===================="
						try {
							sh 'npm run build'
						} catch (Exception e) {
							echo "=============FAILED during BuildArtifact Step====="
							throw e
						}
					}
				}
			}
		}
		/*
		 stage ('Run Unit Tests') {
		 steps {
		 nodejs(nodeJSInstallationName: jsonParams.nodejsTool, configId: 'npm_auth_config') {
		 script {
		 echo "=============Running Unit tests======================="
		 try {
		 sh 'npm run test'
		 } catch (Exception e) {
		 throw e
		 }
		 echo 'Generating test report'
		 }
		 }
		 }
		 }
		 */
		stage ('Artifactory Upload'){
			steps{
				nodejs(nodeJSInstallationName: jsonParams.nodejsTool , configId: 'npm_auth_config') {
					script {
						def myServer = Artifactory.server(jsonParams.artifactoryServer)
						def packageJSON = readJSON file: 'package.json'
						def packageJSONAppName = packageJSON.name
						def packageJSONVersion = packageJSON.version
						def path = jsonParams.artifactPath
						def strPattern = "${path}${packageJSONAppName}-${packageJSONVersion}.zip".trim()
						def uploadSpec
						def exists = fileExists "${strPattern}"
						if (exists) {
							if(!isReleaseBuild){
								uploadSpec = """{
                                "files": [
                                    {
                                        "pattern": "${strPattern}",
                                        "target": "${publishRepo}/${packageJSONAppName}/-/",
                                        "regexp": "false",
                                        "recursive": "true"
                                    }
                                ]
                            }"""
								myServer.upload(uploadSpec)
							} else {
								try {
									sh 'npm publish --registry $artifactPath/$publishRepo'
								} catch (Exception e) {
									throw e
								}
							}
							echo "===================================================================================="
							echo "The build artifact is generated in the path - $WORKSPACE/${strPattern}"
							echo "===================================================================================="
							myServer.publishBuildInfo myBuildInfo
						} else {
							echo "===================================================================================="
							echo "The build artifact is not generated/ Missing in the path provided - ${strPattern}"
							echo "===================================================================================="
							sh "exit 1"
						}
					}
				}
			}
		}
	}
}
