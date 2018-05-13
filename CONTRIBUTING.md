If you have a question about Scanamo there is a 
[Gitter channel](https://gitter.im/guardian/scanamo) to try and 
answer it. Suggestions on how to improve the documentation are very
welcome.

Feel free to open an issue if you notice a bug or have an idea for a
feature. 

Pull requests are gladly accepted. Scanamo follows a standard
[fork and pull](https://help.github.com/articles/using-pull-requests/)
model for contributions via GitHub pull requests.

Building and testing Scanamo
----------------------------

Scanamo uses a standard [SBT](https://www.scala-sbt.org/) build. If you
have SBT installed you can just run the `test` command from the SBT prompt
to compile Scanamo and run its tests.

Most, though not all of Scanamo's tests are from examples in the scaladoc, 
or `README.md`, which are turned into tests by 
[sbt-doctest](https://github.com/tkawachi/sbt-doctest).

Contributing documentation
--------------------------

The [website](http://www.scanamo.org) is built using 
[sbt-microsites](https://47deg.github.io/sbt-microsites/). To check 
documentation changes: 
 * run `makeMicrosite` from the root of SBT
 * run `jekyll serve --incremental --baseurl /` from `docs/target/site`
 * Load http://127.0.0.1:4000/

Releasing
---------

`release cross` from the SBT prompt should publish an artifact to Maven 
Central for both Scala 2.11 and Scala 2.12. It will also attempt to update
the documentation website at http://www.scanamo.org/ with the latest scaladoc.
