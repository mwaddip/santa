// SANTA — JVM blesser (spike)
// Standalone sbt project that links the canonical reference interpreter
// (org.scorexfoundation:sigma-state, the version the JVM reference node pins)
// and evaluates eval-tier ErgoTree byte-vectors to (value, cost) — the
// canonical "blessed" output the conformance vectors are anchored to.
//
// Run:  sbt --batch "run <path-to-eval-vector.json>"

ThisBuild / scalaVersion := "2.12.20"
ThisBuild / organization := "io.santa"

lazy val jvmBlesser = (project in file("."))
  .settings(
    name := "santa-jvm-blesser",
    libraryDependencies ++= Seq(
      // The reference interpreter. 6.0.3 == the version ergo-node-build pins,
      // so cost/sigma-tree output matches the canonical node (OVERRIDES rule 13).
      "org.scorexfoundation" %% "sigma-state" % "6.0.3",
      // JSON in/out for reading the eval vectors.
      // Pinned to 0.13.0 to match the circe version sigma-state 6.0.3 brings
      // transitively (avoids an eviction conflict; 0.13.0 has all we use).
      "io.circe" %% "circe-parser" % "0.13.0",
      // Test framework (munit auto-registers via its service descriptor).
      "org.scalameta" %% "munit" % "0.7.29" % Test
    ),
    // JDK 17 + Scala 2.12: pre-open java.base in case sigma's crypto path
    // reflects into it. Harmless if unused.
    fork := true,
    javaOptions ++= Seq(
      "--add-opens", "java.base/java.lang=ALL-UNNAMED",
      "--add-opens", "java.base/java.util=ALL-UNNAMED"
    )
  )
