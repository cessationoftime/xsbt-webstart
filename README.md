[integrate xsbt-webstart with xsbt-proguard-plugin]: https://github.com/cessationoftime/xsbt-webstart/wiki/Webstart-Integration-with-Proguard-plugin
[SBT]: https://github.com/harrah/xsbt

A Webstart plugin for xsbt

At the moment, this is only a rough draft, just enough to get my own
projects running. Let me know what you think.

To build this code, get and install [SBT] 

Build and publish the plugin:
    git clone git@github.com:ritschwumm/xstb-webstart.git
    cd xstb-webstart
    sbt publish-local
    
Add the plugin to your project in project/plugins/build.sbt:
    addSbtPlugin("de.djini" % "xsbt-webstart" % "0.0.3")
    
Include the plugin in you project's build.sbt:

    seq(WebStartPlugin.allSettings:_*)
    
    webstartMainClass   := "my.Main"
    
    webstartGenConf := GenConf(
        dname       = "CN=Snake Oil, OU=An Anonymous Hacker, O=Bad Guys Inc., L=Bielefeld, ST=33641, C=DE",
        validity    = 365
    )

    webstartKeyConf := KeyConf(
        keyStore    = file("my/keyStore"),
        storePass   = "password",
        alias       = "alias",
        keyPass     = "password"
    )
    
    webstartJnlpConf    := JnlpConf(
        fileName        = "my.jnlp",
        codeBase        = "http://my.test/webstart/",
        title           = "My Title",
        vendor          = "My Company",
        description     = "My Webstart Project",
        offlineAllowed  = true,
        allPermissions  = true,
        j2seVersion     = "1.6+",
        maxHeapSize     = 192
    )

    Optional: if you would like to build a jnlp Applet instead of a jnlp webstart Application then add this section. 
    (broken at the moment, making this mandatory and causing the plugin to only create applets)
    
    webstartApplet     := AppletDescConf(
    	name 			= "DatawarehouseQuery",
    	height			= 600,
    	width			= 600
    )

Optionally, include the name of a jar containing the classes of all your libraries and project, 
this is useful for the output of tools such as proguard:

    webstartSingleJar := "singleJarName_2.9.1-0.0.1-SNAPSHOT.min.jar"

See the wiki to [integrate xsbt-webstart with xsbt-proguard-plugin] and create commands that execute both serially

Once set up you can use the following tasks in sbt:

    webstart                  creates a directory with a JNLP file and all necessary jar files
    webstart-singlejar        creates a directory with a JNLP file and your specified jar file
    webstart-keygen           creates a keyStore, fails if it already exists

