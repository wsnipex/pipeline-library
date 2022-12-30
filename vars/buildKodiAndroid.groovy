def call(Map buildParams = [:]) {
    env.BUILDTHREADS = buildParams.containsKey('buildthreads') && buildParams.buildthreads <= 64 ? buildParams.buildthreads : 64
    env.ARCH = buildParams.arch == 'arm64' ? 'aarch64-linux-android' : 'arm-linux-androideabi'
    env.BUILDSTEPS_DIR = buildParams.arch == 'arm-linux-androideabi' ? 'android' : 'android-arm64-v8a'
    env.SDK_PATH = '/home/jenkins/android-tools/android-sdk-linux/cmdline-tools'
    env.TARBALLS_DIR = '$WORKSPACE/../xbmc-tarballs'
    ccacheDir = buildParams.arch == 'arm64' ? '.ccacheandroid64' : '.ccacheandroidarm'
    env.CCACHE_DIR = '$WORKSPACE/../' + ccacheDir
    env.PUBLISH_FILES = ''
    env.PULLID = ''
    env.PULLREFSPEC = ''

    pipeline {
        options {
            buildDiscarder(logRotator(daysToKeepStr: '7', numToKeepStr: '35', artifactNumToKeepStr: '20'))
        }

        parameters {
            choice(name: 'Configuration', choices: ['Default', 'Debug', 'Release'], description: 'build type')
            string(name: 'Revision', defaultValue: 'master', description: 'master')
            string(name: 'Workspace_Suffix', defaultValue: '', description: 'suffix to append to workspace')
            string(name: 'GITHUB_REPO', defaultValue: 'xbmc', description: 'The repository/fork to build from. (e.x. the name of the github user).')
            choice(name: 'NDK', choices: ['21.4.7075529', '25.1.8937393'], description: 'android NDK')
            booleanParam(name: 'BUILD_BINARY_ADDONS', defaultValue: true, description: 'Whether binary addons should be built during or not.')
            string(name: 'ADDONS', defaultValue: '^peripheral\\.joystick$', description: 'Which binary addons should be built.')
            //booleanParam(name: 'RUN_TEST', defaultValue: false, description: 'Turn this on if you want to build and run the xbmc unit tests based on  gtest.')
            booleanParam(name: 'UPLOAD_RESULT', defaultValue: false, description: 'Whether the resulting builds should be uploaded to test-builds')
            string(name: 'PR', defaultValue: null, description: 'Pull Request to build. Overrides Revision, empty for normal build')
        }

        agent {
            docker {
                image 'kodi/jenkins/android-build:latest'
                label 'gotham'
                args '--userns=keep-id ' +
                    '-v /home/jenkins/jenkins-root/workspace/.ccacheandroidarm:/home/jenkins/jenkins-root/workspace/' + ccacheDir + ':rw ' +
                    '-v /home/jenkins/jenkins-root/workspace/xbmc-tarballs:/home/jenkins/jenkins-root/workspace/xbmc-tarballs:rw ' +
                    '-v /home/jenkins/android-tools/signing:/home/jenkins/android-tools/signing:ro'
                customWorkspace "workspace/$JOB_BASE_NAME" + params.Workspace_Suffix
            }
        }

        stages {
            stage('Checkout Scm') {
                steps {
                    script {
                        if (env.ghprbPullId && env.ghprbPullId != 'null') {
                            env.PULLID = env.ghprbPullId
                            echo "setting PULLID to ghprbPullId: ${PULLID}"
                        }
                        else if (params.PR) {
                            env.PULLID = params.PR
                            echo "setting PULLID to params.PR: ${PULLID}"
                        }
                        if (env.PULLID) {
                            env.Revision = "refs/remotes/${GITHUB_REPO}/pr/${PULLID}/merge"
                            echo "Building Pull Request: ${PULLID}, overriding Revision: ${Revision}"
                            env.PULLREFSPEC = "+refs/pull/${PULLID}/*:refs/remotes/origin/pr/${PULLID}/* +refs/pull/${PULLID}/*:refs/remotes/${GITHUB_REPO}/pr/${PULLID}/*"
                        }
                    }
                    checkout(
                        [$class: 'GitSCM', branches: [[name: "${Revision}"]],
                        browser: [$class: 'GithubWeb', repoUrl: 'https://github.com/${GITHUB_REPO}/xbmc/'],
                            extensions: [[$class: 'CloneOption', noTags: false, reference: '$WORKSPACE/../kodi', shallow: false, timeout: 120],
                            [$class: 'CheckoutOption', timeout: 120], [$class: 'PruneStaleBranch']],
                            userRemoteConfigs: [[credentialsId: 'github-app-xbmc',
                                refspec: "+refs/heads/*:refs/remotes/origin/* +refs/heads/*:refs/remotes/${GITHUB_REPO}/* ${PULLREFSPEC}",
                                url: 'https://github.com/${GITHUB_REPO}/xbmc.git']]
                        ])
                }
            }

            stage('Check depends') {
                steps {
                    script {
                        env.Configuration = params.Configuration == 'Release' ? 'Release' : 'Debug'

                        /* TODO: fix external library
                            verifyResult = buildHash(
                                path: env.WORKSPACE +'tools/depends', config: params.Configuration, sdk: env.SDK_PATH, ndk: env.NDKVER
                            )
                        */
                        hashStr = 'none'
                        hashFromTag = 'invalid'
                        try {
                            rev = sh(returnStdout: true, script: "git rev-list HEAD --max-count=1 $WORKSPACE/tools/depends")
                            hashStr = rev.trim() + "_${Configuration}_${SDK_PATH}_${NDKVER}"
                            hashFromTag = readFile(file: "${WORKSPACE}/tools/depends/.last_success_revision")
                            println 'hashFromTag: ' + hashFromTag
                        }
                        catch (error) {
                            println 'Error verifying depends hash'
                        }

                        env.BUILD_DEPENDS = hashFromTag == hashStr ? 'no' : 'yes'
                        println 'BUILD_DEPENDS: ' + env.BUILD_DEPENDS
                    }
                }
            }

            stage('Build depends') {
                when { equals expected: 'yes', actual: env.BUILD_DEPENDS  }
                steps {
                    script {
                        env.NDKVER = buildParams.containsKey('ndk_version') ? buildParams.ndk_version : params.NDK
                        env.DEBUG_SWITCH = params.Configuration == 'Release' ? '--disable-debug' : '--enable-debug'
                        sh 'bash -c "\
                          cd $WORKSPACE/tools/depends \
                          && git clean -xfd . \
                          && ./bootstrap \
                          && ./configure \
                            --with-tarballs=$TARBALLS_DIR \
                            --host=$ARCH \
                            --with-sdk-path=$SDK_PATH \
                            --with-ndk-path=$SDK_PATH/ndk/$NDKVER \
                            --prefix=$WORKSPACE/tools/depends/xbmc-depends \
                            $DEBUG_SWITCH \
                          && make -j$BUILDTHREADS \
                        "'

                        rev = sh(returnStdout: true, script: "git rev-list HEAD --max-count=1 $WORKSPACE/tools/depends")
                        hashStr = rev.trim() + "_${Configuration}_${SDK_PATH}_${NDKVER}"
                        writeFile file: "${WORKSPACE}/tools/depends/.last_success_revision", text: "${hashStr}"
                    }
                }
            }

            stage('Build binary addons') {
                when { equals expected: true, actual: params.BUILD_BINARY_ADDONS }
                steps {
                    script {
                        env.FAILED_BUILD_FILENAME = '.last_failed_revision'
                        env.BUILD_BINARY_ADDONS = params.BUILD_BINARY_ADDONS
                        env.ADDONS = params.ADDONS
                        result = sh returnStdout: true, script: '''
                            echo "building binary addons: $ADDONS"
                            rm -f $WORKSPACE/cmake/.last_failed_revision
                            cd $WORKSPACE/tools/depends/target/binary-addons
                            make -j$BUILDTHREADS ADDONS=$ADDONS V=1 VERBOSE=1
                          '''

                        hashStr = rev.trim() + "_${Configuration}_${SDK_PATH}_${NDKVER}"
                        if (result =~ /Following Addons failed to build/ ) {
                            writeFile file: "${WORKSPACE}/cmake/.last_failed_revision", text: "${hashStr}"
                        }
                        else {
                            writeFile file: "${WORKSPACE}/cmake/.last_success_revision", text: "${hashStr}"
                        }
                    }
                }
            }

            stage('Build kodi') {
                steps {
                    script {
                        sh '''
                          cd $WORKSPACE
                          rm -rf $WORKSPACE/build
                          make -C $WORKSPACE/tools/depends/target/cmakebuildsys
                          cd build
                          make -j$BUILDTHREADS VERBOSE=1
                        '''
                    }
                }
            }

            stage('Package') {
                steps {
                    withCredentials([string(credentialsId: 'androidSigningKeyPassword', variable: 'SIGNING_KEY')]) {
                        sh '''
                          cd $WORKSPACE/build
                          export KODI_ANDROID_KEY_ALIAS=kodirelease KODI_ANDROID_KEY_PASSWORD=$SIGNING_KEY KODI_ANDROID_STORE_PASSWORD=$SIGNING_KEY KODI_ANDROID_STORE_FILE=/home/jenkins/android-tools/signing/kodi-release.keystore
                          make -j$BUILDTHREADS apk
                        '''
                    }
                    script {
                        env.buildRev = sh(returnStdout: true, script: 'git show -s --abbrev=8  --pretty=format:"%cs_%h"').replace('-', '').replace('_', '-')
                        if (buildParams.arch == 'arm64') {
                            env.filename = params.Configuration == 'Release' ? 'kodiapp-arm64-v8a-release' : 'kodiapp-arm64-v8a-debug'
                            env.uploadFile = 'kodi-' + buildRev + '-arm64-v8a'
                        }
                        else {
                            env.filename = params.Configuration == 'Release' ? 'kodiapp-armeabi-v7a-release' : 'kodiapp-armeabi-v7a-debug'
                            env.uploadFile = 'kodi-' + buildRev + '-armeabi-v7a'
                        }
                        sh '[ -f $WORKSPACE/${filename}.apk ] && mv $WORKSPACE/${filename}.apk $WORKSPACE/${uploadFile}.apk || echo "Kodi APK not found!"'
                        sh '[ -f $WORKSPACE/${filename}.aab ] && mv $WORKSPACE/${filename}.aab $WORKSPACE/${uploadFile}.aab || :'
                        env.PUBLISH_FILES = "${uploadFile}.apk,${uploadFile}.aab"
                        archiveArtifacts artifacts: env.PUBLISH_FILES, followSymlinks: false
                    }
                }
            }
            stage('Upload') {
                when { expression { return params.UPLOAD_RESULT } }
                steps {
                    sshPublisher(
                     publishers: [sshPublisherDesc(configName: 'Mirrors', transfers:
                        [sshTransfer(cleanRemote: false, excludes: '',
                        sourceFiles: env.PUBLISH_FILES,
                        execCommand: '''
                         if [ "$BUILD_CAUSE" == "TIMERTRIGGER" -o "$UPSTREAM_BUILD_CAUSE" == "TIMERTRIGGER" ]; then
                          mv upload/kodi-*.apk /var/www/downloads/nightlies/android/arm/$Revision
                           if [ -f upload/kodi-*.aab ]; then
                            mv upload/kodi-*.aab /var/www/downloads/nightlies/android/arm/$Revision
                          fi
                        else
                          mv upload/kodi-*.apk /var/www/downloads/test-builds/android/arm/
                          if [ -f upload/kodi-*.aab ]; then
                            mv upload/kodi-*.aab /var/www/downloads/test-builds/android/arm/
                          fi
                        fi''',
                        execTimeout: 30000, flatten: false, makeEmptyDirs: false, noDefaultExcludes: false, patternSeparator: '[, ]+',
                        remoteDirectory: 'upload', remoteDirectorySDF: false, removePrefix: '')],
                        usePromotionTimestamp: false, useWorkspaceInPromotion: false, verbose: false)]
                    )
                }
            }
        }
        post {
            always {
                recordIssues filters: [includeFile('xbmc/.*')], qualityGates: [[threshold: 5, type: 'TOTAL', unstable: false]], tools: [clang()]
                addEmbeddableBadgeConfiguration(id: '$BUILD_TAG')
            }
        }
    }
}
