@Library('socrata-pipeline-library@9.9.1') _

commonPipeline(
    jobName: 'secondary-watcher-geocoding',
    language: 'scala',
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
