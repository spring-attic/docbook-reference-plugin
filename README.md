# This repository is no longer actively maintained by VMware, Inc.


## DocBook Plugin for Gradle
Small plugin used internally by the Spring Team to create documentation from docbook
sources.

### Configuration
See [tags][1] to determine the
latest available version. Then configure the plugin in your project as
follows, using [spring-framework/build.gradle][2] as an example:
```groovy
buildscript {
    repositories {
        maven { url 'https://repo.spring.io/plugins-release' }
    }
    dependencies {
        classpath 'io.spring.gradle:docbook-reference-plugin:0.3.0'
    }
}

// ...

configure(rootproject) {
    apply plugin: 'docbook-reference'

    reference {
        sourceDir = file('src/reference/docbook')
        pdfFilename = 'spring-framework-reference.pdf'

        // optionally

        // configure fop for pdf generation
        fopUserConfig = file('src/reference/fop/fop-userconfig.xml')

        // Configure which files have ${} expanded
        expandPlaceholders = '**/index.xml, **/other.xml'
        
        // Delete the index.fo after creating PDF
        retainFo = false
    }

    task docsZip(type: Zip) {
        group = 'Distribution'
        baseName = 'spring-framework'
        classifier = 'docs'

        // ...

        from (reference) {
            into 'reference'
        }
    }

    // ...

    artifacts {
        archives docsZip
    }
}
```
See contents of the [spring-framework/src/reference/docbook][3] for details.


### Usage
```
$ gradle referenceHtmlMulti
$ gradle referenceHtmlSingle
$ gradle referencePdf
$ gradle referenceEpub
$ gradle reference  # all of the above
$ gradle build      # depends on `reference` because of "artifacts" arrangement
```

### Output
```
$ open build/reference/html/index.html
$ open build/reference/htmlsingle/index.html
$ open build/reference/pdf/spring-framework-reference.pdf
$ open build/reference/epub/spring-framework-reference.epub
```

### Maintenance
See [How to release the docbook reference plugin][4] wiki page.

[1]: https://github.com/SpringSource/gradle-plugins/tags
[2]: https://github.com/SpringSource/spring-framework/blob/master/build.gradle
[3]: https://github.com/SpringSource/spring-framework/tree/master/src/reference/docbook
[4]: https://github.com/SpringSource/gradle-plugins/wiki/How-to-release-the-docbook-reference-plugin
