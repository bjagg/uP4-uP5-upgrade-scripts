# uP4-uP5-upgrade-scripts
Misc Clojure scripts to migrate data files

## Setting Up for Clojure Scripts

Clojure is a LISP that runs on the JVM (and in JavaScript). Some environment setup is needed to run these scripts.

### Install SDKMan

SDKMan is a tool that supports installing different versions of Java and other dev tools.
We will use it to install Java and Leiningen, the Clojure build tool.

- Follow instructions at https://sdkman.io/install

### Install Java

You can skip this step if you already have JDK 1.8 or newer. If not, then follow the steps below:

```bash
sdk list java
sdk install java 8.0.275-amzn
```

Above, is an example of installing a version of Java 8 JDK.

### Install Leiningen

If you have the Clojure build tool Leiningen installed, skip this step. Otherwise:

```bash
sdk install leiningen
```
### Install `lein-exec` Plugin

To run Clojure scripts, we need to install the `lein-exec` plugin. We will install it globally.

```bash
mkdir ~/.lein
echo '{:user {:plugins [[lein-exec "0.3.7"]]}}' > ~/.lein/profiles.clj
lein exec -e '(println "hi")'
```

### Install `lein-exec` Shell Scripts

The last step for Clojure scripts is to download and set executable flag on two scripts.

```bash
wget https://raw.github.com/kumarshantanu/lein-exec/master/lein-exec
wget https://raw.github.com/kumarshantanu/lein-exec/master/lein-exec-p
chmod a+x lein-exec lein-exec-p
mv lein-exec lein-exec-p ~/bin  # assuming ~/bin is in PATH
```

## References
- https://sdkman.io/
- https://leiningen.org/
- https://github.com/kumarshantanu/lein-exec
