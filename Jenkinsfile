@Library('socrata-pipeline-library@6.0.0') _

commonPipeline(
  jobName: 'secondary-watcher-geocoding',
  language: 'scala',
  projects: [
    [
      name: 'secondary-watcher-geocoding',
      compiled: true,
      deploymentEcosystem: 'marathon-mesos',
      marathonInstanceNamePattern: 'secondary-watcher-geocoding*',
      paths: [
        dockerBuildContext: 'docker',
      ],
      type: 'service',
    ]
  ],
  teamsChannelWebhookId: 'WORKFLOW_IQ',
)
