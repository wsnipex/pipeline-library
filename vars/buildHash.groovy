def call(Map hashParams) {
/*
def write(String path) {
	def hashStr = get(path)
	writeFile file: path +'/.last_success_revision', text: "${hashStr}"
}
*/
	println "getting hash for path: " + hashParams.path
	env.path = hashParams.path
	def rev = ""
	def hashStr = "none"
	def hashFromTag = "invalid"

	try {
		rev = sh(returnStdout: true, script: 'git rev-list HEAD --max-count=1 $path')
		hashStr = rev.replace("\n", "") + "_" + hashParams.config + "_" + hashParams.sdk + "_" + hashParams.ndk
	}
	catch (error) {
		return false
	}
	println "rev: " + rev
	println "hashStr: " + hashStr

	try {
		hashFromTag = readFile(file: hashParams.path +'/.last_success_revision')
	}
	catch (error) {
		return false
	}
	println "hashFromTag: " + hashFromTag
	return hashFromTag == hashStr
}
