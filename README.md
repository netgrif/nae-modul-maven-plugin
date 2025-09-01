# Netgrif Application Engine Module Maven Plugin

Maven plugin for building NAE (Netgrif Application Engine) modules.

- GroupId: `com.netgrif`
- ArtifactId: `nae-module-maven-plugin`
- Latest version: `1.3.0`
- Project site: [https://netgrif.github.io/nae-module-maven-plugin](https://netgrif.github.io/nae-module-maven-plugin)
- Issue
  tracker: [https://github.com/netgrif/nae-modul-maven-plugin/issues](https://github.com/netgrif/nae-modul-maven-plugin/issues)
- License: Apache-2.0

## Features

- Streamlines the build lifecycle for NAE modules
- Provides Maven goals to validate and package modules consistently
- Integrates with Maven reporting

## Requirements

- Java 21 (JDK 21) to build and run
- Apache Maven (any current stable version should work)

## Goals

- build - building package of NAE module

## Usage

### Add as the maven plugin into the pom.xml

```xml

<plugin>
    <groupId>com.netgrif</groupId>
    <artifactId>nae-module-maven-plugin</artifactId>
    <version>1.3.0</version>
    <configuration>
        <singleOutput>true</singleOutput>
        <excludes>
            <exclude>*lombok*</exclude>
        </excludes>
    </configuration>
    <executions>
        <execution>
            <id>build-module</id>
            <goals>
                <goal>build</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

### Invoke a goal

You can run any goal directly with its fully qualified name:

``` bash
mvn com.netgrif:nae-module-maven-plugin:1.3.0:build
```

## Building from source

``` bash
# Clone
git clone https://github.com/netgrif/nae-modul-maven-plugin.git
cd nae-modul-maven-plugin

# Build
mvn -U -DskipTests clean install
```

Prerequisites:

- JDK 21 available on PATH (JAVA_HOME set to your JDK 21)
- Maven installed

## Contributing

Contributions are welcome!

- Read [contribution guidelines](https://github.com/netgrif/nae-modul-maven-plugin?tab=contributing-ov-file).
- Report
  issues: [https://github.com/netgrif/nae-modul-maven-plugin/issues](https://github.com/netgrif/nae-modul-maven-plugin/issues).
- Open pull requests with clear descriptions.
- Please follow standard Maven project conventions and include tests where applicable.

## License

Licensed under the Apache License, Version 2.0.

- License text: [https://www.apache.org/licenses/LICENSE-2.0.txt](https://www.apache.org/licenses/LICENSE-2.0.txt)
