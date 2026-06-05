// SANTA — JVM blesser
// Standalone sbt project that links the canonical reference interpreter
// (org.scorexfoundation:sigma-state, the version the JVM reference node pins)
// and evaluates eval-tier ErgoTree byte-vectors to (value, cost) — the
// canonical "blessed" output the conformance vectors are anchored to.
//
// Run:  sbt --batch "run <path-to-eval-vector.json>"

ThisBuild / scalaVersion := "2.12.20"
ThisBuild / organization := "io.santa"

// Tx-tier blesser: ergo-core is NOT on Maven; publishLocal'd from ergo-node-build@v6.0.2.1.
// Maintainer-only + path-gated — set SANTA_TX_BLESSER=1 to compile the tx blesser + bless.
// Unset (incl. CI): the dep and the santa/txbless sources are excluded; eval/wire/rudolph build untouched.
val txBlesserEnabled = sys.env.get("SANTA_TX_BLESSER").exists(v => v == "1" || v.equalsIgnoreCase("true"))

lazy val jvmBlesser = (project in file("."))
  .settings(
    name := "santa-jvm-blesser",
    // Resolvers for ergo-core's transitive deps: the node pulls leveldbjni-all from a
    // GitLab Maven registry, and some deps live on Sonatype. Only needed when the tx
    // blesser is enabled; excluded unconditionally so CI never resolves ergo-core.
    resolvers ++= (if (txBlesserEnabled) Seq(
      "Sonatype Releases" at "https://oss.sonatype.org/content/repositories/releases/",
      "Repo for leveldbjni-all" at "https://gitlab.com/api/v4/projects/61211221/packages/maven"
    ) else Seq.empty),
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
      // V6 extractor (Phase 2): drives the executable language spec to generate
      // santa-eval/v2 vectors. The sigma-state tests-classifier jar bundles every
      // sub-module's Test classes (root project aggregates Test/packageBin across
      // core/data/interpreter/parsers/sdk/sc), incl. `sigma.LanguageSpecificationV6`
      // and the SigmaDslTesting / CompilerTestingCommons framework.
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
    // Tx-tier blesser: ergo-core (+ ergo-wallet for ErgoInterpreter; avldb transitively)
    // publishLocal'd at 6.0.2.1 from ergo-node-build@v6.0.2.1 — NOT on Maven.
    // Drives ErgoTransaction.validateStateful to bless captured tx vectors.
    // Excluded when SANTA_TX_BLESSER is unset so CI never attempts to resolve ergo-core.
    libraryDependencies ++= (if (txBlesserEnabled) Seq(
      "org.ergoplatform"  %% "ergo-core"               % "6.0.2.1" % Test,
      "org.ergoplatform"  %% "ergo-wallet"             % "6.0.2.1" % Test
    ) else Seq.empty),
    // The tx-blesser sources (which import ergo-core) only compile when enabled.
    Test / unmanagedSourceDirectories ++= (if (txBlesserEnabled)
      Seq((Test / sourceDirectory).value / "scala-txbless") else Seq.empty),
    // JDK 17 + Scala 2.12: pre-open java.base in case sigma's crypto path
    // reflects into it. Harmless if unused.
    fork := true,
    javaOptions ++= Seq(
      "--add-opens", "java.base/java.lang=ALL-UNNAMED",
      "--add-opens", "java.base/java.util=ALL-UNNAMED"
    )
  )
