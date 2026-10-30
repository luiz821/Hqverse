# junrar usa reflexão leve em alguns pontos e commons-logging/slf4j opcionais
-dontwarn org.slf4j.**
-dontwarn org.apache.commons.logging.**
-keep class com.github.junrar.** { *; }
