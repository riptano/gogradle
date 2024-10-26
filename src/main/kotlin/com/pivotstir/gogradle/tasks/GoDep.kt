package com.pivotstir.gogradle.tasks

import com.pivotstir.gogradle.*
import kotlinx.coroutines.*
import org.gradle.api.Project
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.process.internal.ExecException
import org.rauschig.jarchivelib.ArchiveFormat
import org.rauschig.jarchivelib.ArchiverFactory
import java.io.ByteArrayOutputStream
import java.io.File

class GoDepConfig(
        val project: Project,
        @Input @Optional var cmdArgs: List<String> = emptyList(),
        @Input @Optional var envs: Map<String, Any> = emptyMap(),
        val minProtoVersion: String = "3.8.0",
        @Input @Optional var thirdpartyIgnored: Boolean = false,
        @Input @Optional var protoVersion: String = minProtoVersion,
        @Input @Optional val swaggoVersion: String = "1.5.1"
)

@GradleSupport
class GoDep : AbstractGoTask<GoDepConfig>(GoDepConfig::class) {

    companion object {
        fun downloadProtoUrl(version: String, platform: String, arch: String) =
                "https://github.com/google/protobuf/releases/download/v$version/protoc-$version-$platform-$arch.zip".toURL()
    }

    init {
        group = GoPlugin.NAME
        description = "Resolve Go project library and 3rd party tool dependencies"

        dependsOn(taskName(GoEnv::class))
    }

    val protocFile: File by lazy {
        File(pluginExtension.pluginConfig.protoDir, listOf("bin", "protoc").joinToString(File.separator))
    }

    override fun run() {
        super.run()

        checkVersion("Protoc", config.protoVersion, config.minProtoVersion)

        runBlocking {
            delay(3000)

            val tasks: MutableList<Deferred<Any?>> = mutableListOf()

            tasks.add(downloadProtoTools())

            if (config.thirdpartyIgnored) {
                logger.lifecycle("Ignoring to download 3rd tools")
            }

            tasks.add(download3rdToolsAndDependentLibs(config.thirdpartyIgnored))

            val errs = awaitAll(*tasks.toTypedArray())

            errs.forEach {
                if (it is RuntimeException) {
                    throw it
                }
            }
        }
    }

    private fun downloadProtoTools(): Deferred<Any?> = GlobalScope.async {
        return@async try {
            if (protocFile.exists()) {
                val out = ByteArrayOutputStream()
                val process = exec("$protocFile --version") {
                    it.standardOutput = out
                }

                if (process.exitValue == 0 && """libprotoc ${config.protoVersion}""".toRegex().containsMatchIn(out.toString())) {
                    logger.lifecycle("Protoc found locally (${config.protoVersion})\n'${out.toString().trim()}'")
                    return@async
                }
            }

            var (platform, arch) = getOsArch()

            arch = when (arch) {
                "amd64" -> "x86_64"
                "i386" -> "x86_32"
                else -> arch
            }

            if (platform == "darwin") {
                platform = "osx"
            }

            // download
            downloadArtifact(
                    "Proto archive",
                    downloadProtoUrl(config.protoVersion, platform, arch),
                    pluginExtension.pluginConfig.protoDir,
                    ArchiverFactory.createArchiver(ArchiveFormat.ZIP)
            )
            protocFile.setExecutable(true)
        } catch (e: Throwable) {
            e
        }
    }

    private fun download3rdToolsAndDependentLibs(nonTestLibsIgnored: Boolean = false): Deferred<Any?> = GlobalScope.async {
        return@async try {
            logger.lifecycle("Generating go.mod")

            val out = ByteArrayOutputStream()

            val result = "go mod init ${pluginExtension.pluginConfig.modulePath}".let {
                logger.lifecycle("Generating go.mod. Cmd: $it")

                exec(it) { spec ->
                    spec.environment.putAll(goEnvs(spec.environment))
                    spec.environment["GO111MODULE"] = "on"
                    spec.standardOutput = out

                    spec.isIgnoreExitValue = true
                }
            }

            when {
                result.exitValue == 1 -> {
                    logger.lifecycle("go.mod already exists")
                }

                result.exitValue != 0 -> {
                    throw ExecException("Error to execute go command: return code = ${result.exitValue}, $out")
                }
            }

            /*
                download    download modules to local cache
                edit        edit go.mod from tools or scripts
                graph       print module requirement graph
                init        initialize new module in current directory
                tidy        add missing and remove unused modules
                vendor      make vendored copy of dependencies
                verify      verify dependencies have expected content
                why         explain why packagePaths or modules are needed
             */

            // install by go get
            val cmds = mutableListOf(
                    "github.com/wadey/gocovmerge@latest",
                    "github.com/axw/gocov/gocov@v1.1.0",
                    "github.com/AlekSi/gocov-xml@v1.1.0"
            )

            if (!nonTestLibsIgnored) {
                cmds.addAll(
                        listOf(
                                "github.com/grpc-ecosystem/grpc-gateway/protoc-gen-grpc-gateway",
                                "github.com/grpc-ecosystem/grpc-gateway/protoc-gen-swagger",
                                "github.com/golang/protobuf/protoc-gen-go",
                                "google.golang.org/grpc"
                        )
                )
            }

            cmds.forEach {
                logger.lifecycle("Starting to install $it to \$GOPATH/bin or \$GOBIN")
            }

            ("go get -d".tokens() + cmds).joinToString(" ").let {
                logger.lifecycle("Downloading Go module dependencies. Cmd: $it")

                exec(it) { spec ->
                    spec.environment.putAll(goEnvs(spec.environment))
                    spec.environment["GO111MODULE"] = "on"
                }
            }

            ("go install".tokens() + "github.com/axw/gocov/gocov@v1.1.0").joinToString(" ").let {
                logger.lifecycle("Installing Go module dependencies. Cmd: $it")

                exec(it) { spec ->
                    spec.environment.putAll(goEnvs(spec.environment))
                    spec.environment["GO111MODULE"] = "on"
                }
            }

            ("go install".tokens() + "github.com/AlekSi/gocov-xml@v1.1.0").joinToString(" ").let {
                logger.lifecycle("Installing Go module dependencies. Cmd: $it")

                exec(it) { spec ->
                    spec.environment.putAll(goEnvs(spec.environment))
                    spec.environment["GO111MODULE"] = "on"
                }
            }

            if (!nonTestLibsIgnored) {
                // install specific version of modules (go get can not support to install versioned module under GOPATH)
                logger.lifecycle("Installing swaggo command (${config.swaggoVersion})")

                val gopathDir = task<GoEnv>()!!.goPathDir
                val swaggoDir = File(gopathDir, "src/github.com/swaggo".split("/").joinToString(File.separator)).apply {
                    mkdirs()
                }

                val cmds = listOf(
                        "git clone -b v${config.swaggoVersion} https://github.com/swaggo/swag.git",
                        "go get -d github.com/swaggo/swag/cmd/swag",
                        "go install github.com/swaggo/swag/cmd/swag"
                )

                cmds.forEachIndexed { index, s ->
                    exec(s) { spec ->
                        if (index == 0) {
                            // ignore git clone if the folder already exists
                            spec.isIgnoreExitValue = true
                        }

                        spec.workingDir(swaggoDir)

                        spec.environment.putAll(goEnvs(spec.environment))
                        spec.environment["GO111MODULE"] = "on"
                    }
                }
            }

            logger.lifecycle("Updating Go library dependencies in go.mod")

            val pkgs = pluginExtension.dependenciesConfig.dependencies()

            if (pkgs.isEmpty()) {
                return@async
            }

            pkgs.map {
                logger.lifecycle("Starting to update ${it.path}")
            }

            pkgs.forEach { pkg ->
                ("go get -d".tokens() + config.cmdArgs + listOf(pkg.path)).joinToString(" ").let {
                    logger.lifecycle("Updating Go package dependencies. Cmd: $it")

                    exec(it) { spec ->
                        spec.environment.putAll(goEnvs(spec.environment))
                        spec.environment["GO111MODULE"] = "on"
                        spec.environment.putAll(config.envs)
                    }
                }
            }

            exec("go mod tidy") { spec ->
                spec.environment.putAll(goEnvs(spec.environment))
                spec.environment["GO111MODULE"] = "on"
                spec.environment.putAll(config.envs)
            }
        } catch (e: Throwable) {
            e
        }
    }
}