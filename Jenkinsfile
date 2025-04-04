@Library('socrata-pipeline-library@testing') _

commonServicePipeline(
  defaultBuildWorker: 'build-worker',
  instanceNamePattern: 'secondary-watcher-geocoding*',
  language: 'scala',
  languageOptions: [
    version: '2.11',
    crossCompile: true,
    multiProjectBuild: false,
  ],
  projects: [
    [
      name: 'secondary-watcher-geocoding',
      type: 'service',
      deploymentEcosystem: 'marathon-mesos',
      compile: true  // Sane default
    ],
  ],
  teamsChannelWebhookId: 'WORKFLOW_IQ',
)
