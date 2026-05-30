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
      "org.scalameta" %% "munit" % "0.7.29" % Test,
      // --- SPIKE: V6 extractor (Phase 2) ---
      // The sigma-state tests-classifier jar bundles every sub-module's Test
      // classes (root project aggregates Test/packageBin across core/data/
      // interpreter/parsers/sdk/sc), incl. `sigma.LanguageSpecificationV6` and
      // the SigmaDslTesting / CompilerTestingCommons framework.
      "org.scorexfoundation" %% "sigma-state" % "6.0.3" % Test classifier "tests",
      // Test deps the V6 spec + framework transitively use (versions matched to
      // sigma-state 6.0.3's own build.sbt to avoid eviction).
      "org.scalatest"     %% "scalatest"               % "3.2.14"  % Test,
      "org.scalactic"     %% "scalactic"               % "3.2.14"  % Test,
      "org.scalacheck"    %% "scalacheck"              % "1.15.2"  % Test,
      "org.scalatestplus" %% "scalacheck-1-15"         % "3.2.3.0" % Test,
      // The V6 spec's framework pretty-prints expected expressions / suggestions via
      // `SigmaPPrint` (com.lihaoyi:pprint). Without it, the property bodies that print
      // (decodeNbits/encodeNbits/some/none/AvlTree equivalence) throw
      // NoClassDefFoundError mid-body and their verifyCases never fire — silently
      // shrinking the captured corpus. Pinned to sigma-state 6.0.3's own pprint.
      "com.lihaoyi"       %% "pprint"                  % "0.6.3"   % Test
    ),
    // JDK 17 + Scala 2.12: pre-open java.base in case sigma's crypto path
    // reflects into it. Harmless if unused.
    fork := true,
    javaOptions ++= Seq(
      "--add-opens", "java.base/java.lang=ALL-UNNAMED",
      "--add-opens", "java.base/java.util=ALL-UNNAMED"
    )
  )
