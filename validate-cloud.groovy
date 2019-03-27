/**
 *
 * Launch validation of the cloud with Rally
 *
 * Expected parameters:
 *
 *   ACCUMULATE_RESULTS          If true, results from the previous build will be used
 *   JOB_TIMEOUT                 Job timeout in hours
 *   TEST_IMAGE                  Docker image link
 *   TARGET_NODE                 Salt target for tempest node
 *   SALT_MASTER_URL             URL of Salt master
 *   SALT_MASTER_CREDENTIALS     Credentials to the Salt API
 *
 *   Additional validate job YAML params:
 *
 *   Rally
 *
 *   AVAILABILITY_ZONE           The name of availability zone
 *   FLOATING_NETWORK            The name of the external(floating) network
 *   K8S_RALLY                   Use Kubernetes Rally plugin for testing K8S cluster
 *   STACKLIGHT_RALLY            Use Stacklight Rally plugin for testing Stacklight
 *   RALLY_IMAGE                 The name of the image for Rally tests
 *   RALLY_FLAVOR                The name of the flavor for Rally image
 *   RALLY_PLUGINS_REPO          Git repository with Rally plugins
 *   RALLY_PLUGINS_BRANCH        Git branch which will be used during the checkout
 *   RALLY_CONFIG_REPO           Git repository with files for Rally
 *   RALLY_CONFIG_BRANCH         Git branch which will be used during the checkout
 *   RALLY_SCENARIOS             Path to file or directory with rally scenarios
 *   RALLY_SL_SCENARIOS          Path to file or directory with stacklight rally scenarios
 *   RALLY_TASK_ARGS_FILE        Path to file with rally tests arguments
 *   RALLY_DB_CONN_STRING        Rally-compliant DB connection string for long-term storing
                                 results to external DB
 *   RALLY_TAGS                  List of tags for marking Rally tasks. Can be used when
                                 generating Rally trends based on particular group of tasks
 *   RALLY_TRENDS                If enabled, generate Rally trends report. Requires external DB
                                 connection string to be set. If RALLY_TAGS was set, trends will
                                 be generated based on finished tasks with these tags, otherwise
                                 on all the finished tasks available in DB
 *   SKIP_LIST                   List of the Rally scenarios which should be skipped
 *   REPORT_DIR                  Path for reports outside docker image
 *
 */

common = new com.mirantis.mk.Common()
test = new com.mirantis.mk.Test()
validate = new com.mirantis.mcp.Validate()
def python = new com.mirantis.mk.Python()

def pepperEnv = "pepperEnv"
def artifacts_dir = 'validation_artifacts/'
def VALIDATE_PARAMS = readYaml(text: env.getProperty('VALIDATE_PARAMS')) ?: [:]
if (! VALIDATE_PARAMS) {
    throw new Exception("VALIDATE_PARAMS yaml is empty.")
}

if (env.JOB_TIMEOUT == ''){
    job_timeout = 12
} else {
    job_timeout = env.JOB_TIMEOUT.toInteger()
}
timeout(time: job_timeout, unit: 'HOURS') {
    node() {
        try {

            stage('Setup virtualenv for Pepper') {
                python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
            }

            stage('Configure') {
                test.install_docker(pepperEnv, TARGET_NODE)
                if (ACCUMULATE_RESULTS.toBoolean() == false) {
                    sh "rm -r ${artifacts_dir}"
                }
                sh "mkdir -p ${artifacts_dir}"
            }

            stage('Run Rally tests') {

                def rally = VALIDATE_PARAMS.get('rally') ?: []
                def tags = rally.get('RALLY_TAGS') ?: []
                def report_dir = rally.REPORT_DIR ?: '/root/qa_results'
                def platform = ["type":"unknown", "stacklight_enabled":false]
                def rally_variables = []
                if (rally.K8S_RALLY.toBoolean() == false) {
                  platform['type'] = 'openstack'
                  rally_variables = ["floating_network=${rally.FLOATING_NETWORK}",
                                     "rally_image=${rally.RALLY_IMAGE}",
                                     "rally_flavor=${rally.RALLY_FLAVOR}",
                                     "availability_zone=${rally.AVAILABILITY_ZONE}"]
                } else {
                  platform['type'] = 'k8s'
                }
                if (rally.STACKLIGHT_RALLY.toBoolean() == true) {
                  platform['stacklight_enabled'] = true
                }
                validate.runRallyTests(
                    pepperEnv, TARGET_NODE, TEST_IMAGE,
                    platform, artifacts_dir, rally.RALLY_CONFIG_REPO,
                    rally.RALLY_CONFIG_BRANCH, rally.RALLY_PLUGINS_REPO,
                    rally.RALLY_PLUGINS_BRANCH, rally.RALLY_SCENARIOS,
                    rally.RALLY_SL_SCENARIOS, rally.RALLY_TASK_ARGS_FILE,
                    rally.RALLY_DB_CONN_STRING, tags,
                    rally.RALLY_TRENDS, rally_variables,
                    report_dir, rally.SKIP_LIST
                )
            }

            stage('Collect results') {
                archiveArtifacts artifacts: "${artifacts_dir}/*"
            }

        } catch (Throwable e) {
            currentBuild.result = "FAILURE"
            currentBuild.description = currentBuild.description ? e.message + " " + currentBuild.description : e.message
            throw e
        }
    }
}
