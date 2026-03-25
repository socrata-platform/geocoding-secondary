@Library('socrata-pipeline-library@9.9.2') _

commonPipeline(
    jobName: 'secondary-watcher-geocoding',
    language: 'scala',
    defaultBuildWorker: 'worker-java-multi-pg13',
    projects: [
        [
          name: 'secondary-watcher-geocoding',
          compiled: true,
          deploymentEcosystem: 'ecs',
          paths: [
              dockerBuildContext: 'docker',
          ],
          type: 'service',
        ]
    ],
    teamsChannelWebhookId: 'WORKFLOW_EGRESS_AUTOMATION',
)
