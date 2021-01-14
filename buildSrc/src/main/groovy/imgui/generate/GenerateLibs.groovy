package imgui.generate

import com.badlogic.gdx.jnigen.*
import groovy.transform.CompileStatic
import org.gradle.api.DefaultTask
import org.gradle.api.file.CopySpec
import org.gradle.api.tasks.TaskAction

@CompileStatic
class GenerateLibs extends DefaultTask {
    String description = 'Generates native libraries using classes under ":imgui-binding" and Dear ImGui itself from "imgui" submodule.'

    private final String[] buildEnvs = System.getProperty('envs')?.split(',')
    private final boolean forWin32 = false;//buildEnvs?.contains('win32')
    private final boolean forWin64 = false;//buildEnvs?.contains('win64')
    private final boolean forLinux32 = true;//buildEnvs?.contains('linux32')
    private final boolean forLinux64 = false;//buildEnvs?.contains('linux64')
    private final boolean forMac64 = false;//buildEnvs?.contains('mac64')

    private final boolean isLocal = System.properties.containsKey("local")
    private final boolean withFreeType = false;//System.properties.containsKey("withFreeType")

    private final String sourceDir = project.file('src/main/java')
    private final String classpath = project.file('build/classes/java/main')
    private final String jniDir = (isLocal ? project.buildDir.path : '/tmp/imgui') + '/jni'
    private final String tmpFolder = (isLocal ? project.buildDir.path : '/tmp/imgui') + '/tmp'
    private final String libsFolder = 'libsNative'

    @TaskAction
    void generate() {
        println 'Generating Native Libraries...'
        println "Build environments: $buildEnvs"
        println "Local mode: $isLocal"
        println "With FreeType: $withFreeType"
        println '====================================='

        // Generate h/cpp files for JNI
        new NativeCodeGenerator().generate(sourceDir, classpath, jniDir)

        // Copy ImGui h/cpp files
        project.copy { CopySpec spec ->
            spec.from(project.rootProject.file('imgui')) { CopySpec it -> it.include('*.h', '*.cpp') }

            if (withFreeType) {
                spec.from(project.rootProject.file('imgui/misc/freetype')) { CopySpec it -> it.include('*.h', '*.cpp') }
            }

            spec.from(project.rootProject.file('imgui-binding/src/main/native'))
            spec.into(jniDir)
        }

        // Generate platform dependant ant configs and header files
        def buildConfig = new BuildConfig('imgui-java', tmpFolder, libsFolder, jniDir)
        def buildTargets = [] as BuildTarget[]

        if (forWin32) {
            def win32 = BuildTarget.newDefaultTarget(BuildTarget.TargetOs.Windows, false)

            if (withFreeType) {
                win32.cppFlags += ' -fstack-protector -I/usr/include/freetype2 -I/usr/include/libpng16 -I/usr/include/harfbuzz -I/usr/include/glib-2.0 -I/usr/lib/glib-2.0/include'
                win32.libraries += '-lfreetype -lbz2 -lssp'
            }

            buildTargets += win32
        }
        if (forWin64) {
            def win64 = BuildTarget.newDefaultTarget(BuildTarget.TargetOs.Windows, true)

            if (withFreeType) {
                win64.cppFlags += ' -I/usr/include/freetype2 -I/usr/include/libpng16 -I/usr/include/harfbuzz -I/usr/include/glib-2.0 -I/usr/lib/glib-2.0/include'
                win64.libraries += '-lfreetype -lbz2 -lssp'
            }

            buildTargets += win64
        }

        if (forLinux32) {
            def linux32 = BuildTarget.newDefaultTarget(BuildTarget.TargetOs.Linux, false, true)

            if (withFreeType) {
                linux32.cppFlags += ' -I/usr/include/freetype2 -I/usr/include/libpng16 -I/usr/include/harfbuzz -I/usr/include/glib-2.0 -I/usr/lib/glib-2.0/include'
                linux32.linkerFlags += ' -lfreetype'
            }

            buildTargets += linux32
        }
        if (forLinux64) {
            def linux64 = BuildTarget.newDefaultTarget(BuildTarget.TargetOs.Linux, true, true)

            if (withFreeType) {
                linux64.cppFlags += ' -I/usr/include/freetype2 -I/usr/include/libpng16 -I/usr/include/harfbuzz -I/usr/include/glib-2.0 -I/usr/lib/glib-2.0/include'
                linux64.linkerFlags += ' -lfreetype'
            }

            buildTargets += linux64
        }

        if (forMac64) {
            def mac64 = BuildTarget.newDefaultTarget(BuildTarget.TargetOs.MacOsX, true)
            mac64.cppFlags += ' -stdlib=libc++'
            mac64.cppFlags = mac64.cppFlags.replaceAll('10.5', '10.9')
            mac64.linkerFlags = mac64.linkerFlags.replaceAll('10.5', '10.9')

            if (withFreeType) {
                mac64.cppFlags += ' -I/usr/local/include/freetype2 -I/usr/local/include/libpng16 -I/usr/local/include/harfbuzz -I/usr/local/include/glib-2.0 -I/usr/local/lib/glib-2.0/include'
                mac64.linkerFlags += ' -lfreetype'
            }

            buildTargets += mac64
        }

        new AntScriptGenerator().generate(buildConfig, buildTargets)

        if (!withFreeType) {
            project.delete {
                it.delete("$jniDir/imgui.ImGuiFreeType.cpp", "$jniDir/imgui.ImGuiFreeType.h")
            }
        }

        // Generate native libraries
        // Comment/uncomment lines with OS you need.

        if (forWin32)
            BuildExecutor.executeAnt(jniDir + '/build-windows32.xml', '-v', '-Dhas-compiler=true', '-Drelease=true', 'clean', 'postcompile')
        if (forWin64)
            BuildExecutor.executeAnt(jniDir + '/build-windows64.xml', '-v', '-Dhas-compiler=true', '-Drelease=true', 'clean', 'postcompile')
        if (forLinux32)
            BuildExecutor.executeAnt(jniDir + '/build-linuxarm32.xml', '-v', '-Dhas-compiler=true', '-Drelease=true', 'clean', 'postcompile')
        if (forLinux64)
            BuildExecutor.executeAnt(jniDir + '/build-linuxarm64.xml', '-v', '-Dhas-compiler=true', '-Drelease=true', 'clean', 'postcompile')
        if (forMac64)
            BuildExecutor.executeAnt(jniDir + '/build-macosx64.xml', '-v', '-Dhas-compiler=true', '-Drelease=true', 'clean', 'postcompile')

        BuildExecutor.executeAnt(jniDir + '/build.xml', '-v', 'pack-natives')
    }
}
