def call(String path) {
	def rev = sh(returnStdout: true, script: "git rev-list HEAD --max-count=1  -- ${path}").trim()
	def hashStr = rev + "_" + params.Configuration + "_" + SDK_PATH + "_" + NDKVER
	writeFile file: "${path}/.last_success_revision", text: "${hashStr}"
}
