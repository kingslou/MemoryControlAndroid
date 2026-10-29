plugins {
    id("com.android.library") version "8.4.2"
    id("com.android.application") version "8.4.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24"
    `maven-publish`
}

group = "com.github.kingslou"
version = "1.1.1"

android {
    namespace = "io.github.kingslou.memorycontrol"
    compileSdk = 34
    defaultConfig { minSdk = 23 }
    resourcePrefix = "mc_"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
        // 保持 Kotlin 1.7 宿主可读取的元数据，并限制源码使用的标准库 API。
        languageVersion = "1.7"
        apiVersion = "1.7"
    }
    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}

dependencies {
    // 显式声明兼容基线，避免将编译器版本对应的标准库传递给旧宿主。
    api("org.jetbrains.kotlin:kotlin-stdlib:1.7.10")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = project.group.toString()
                artifactId = "MemoryControlAndroid"
                version = project.version.toString()
                pom {
                    name.set("MemoryControlAndroid")
                    description.set("Android floating memory monitor and Java/Native pressure simulator")
                    url.set("https://github.com/kingslou/MemoryControlAndroid")
                    scm {
                        url.set("https://github.com/kingslou/MemoryControlAndroid")
                        connection.set("scm:git:https://github.com/kingslou/MemoryControlAndroid.git")
                    }
                }
            }
        }
    }
}
