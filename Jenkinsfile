@Library('socrata-pipeline-library@3.2.0') _

commonPipeline(
  defaultBuildWorker: 'build-worker',
  jobName: 'secondary-watcher-geocoding',
  language: 'scala',
  languageVersion: '2.12',
  projects: [
    [
      name: 'secondary-watcher-geocoding',
      deploymentEcosystem: 'marathon-mesos',
      marathonInstanceNamePattern: 'secondary-watcher-geocoding*',
      type: 'service',
    ]
  ],
  teamsChannelWebhookId: 'WORKFLOW_IQ',
)
