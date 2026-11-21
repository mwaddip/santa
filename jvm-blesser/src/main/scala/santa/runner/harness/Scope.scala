package santa.runner.harness

import io.circe.Json
import io.circe.parser.parse
import java.nio.file.{Files, Path, Paths}
import scala.sys.process._
import scala.util.Try

/** What a runner declares it implements (runner-integration §2): it decides which vector files the runner grades. */
final case class Scope(version: String, tiers: Seq[String], cost: Boolean, kinds: Option[Seq[String]], activation: Boolean)

object Scope {
  val Versions: Seq[String] = Seq("v4", "v5", "v6")
  val Tiers: Seq[String] = Seq("eval", "wire", "transaction", "block", "chain", "authds", "nipopow")
  val WireKinds: Seq[String] = Seq("Constant", "Box", "Transaction", "Header", "SigmaBoolean", "ErgoTree", "BlockTransactions")
  /** SANTA's entry keys and the scope keys: a misspelt key would silently fall back to a default. */
  val Keys: Set[String] = Set("name", "label", "impl", "control", "runner", "version", "tiers", "cost", "kinds", "activation")

  /** The scope keys of a runner.json, refused as SANTA's conform refuses them. */
  def parse(j: Json): Either[String, Scope] = {
    def fail(s: String) = Left(s"runner.json: $s")
    val m = j.asObject.getOrElse(return fail("not an object"))
    m.keys.find(k => !Keys.contains(k)) match {
      case Some(k) => return fail(s"unknown key ${q(k)}")
      case None    =>
    }
    val version = m("version").flatMap(_.asString).getOrElse(return fail("no \"version\""))
    if (!Versions.contains(version)) return fail(s"unknown version ${q(version)}")
    def names(k: String): Either[String, Seq[String]] =
      m(k).flatMap(_.asArray).map(a => a.map(_.asString)) match {
        case Some(xs) if xs.forall(_.isDefined) => Right(xs.flatten)
        case _                                  => fail(s"${q(k)} is not a list of strings")
      }
    def distinct(xs: Seq[String], known: Seq[String], what: String): Either[String, Unit] =
      if (xs.isEmpty) fail(s"$what is empty")
      else xs.zipWithIndex.collectFirst {
        case (x, _) if !known.contains(x)        => s"unknown $what ${q(x)}"
        case (x, i) if xs.take(i).contains(x)    => s"$what ${q(x)} listed twice"
      }.fold[Either[String, Unit]](Right(()))(fail)
    def flag(k: String): Either[String, Boolean] = m(k) match {
      case None    => Right(true)
      case Some(v) => v.asBoolean.toRight(s"runner.json: ${q(k)} is not a boolean")
    }
    for {
      tiers <- names("tiers")
      _ <- distinct(tiers, Tiers, "tier")
      kinds <- m("kinds") match {
        case None => Right(None)
        case Some(v) if v.isNull => Right(None)
        case Some(_) =>
          for {
            ks <- names("kinds")
            _ <- distinct(ks, WireKinds, "wire kind")
            _ <- if (tiers.contains("wire")) Right(()) else fail("\"kinds\" declared without the wire tier")
          } yield Some(ks)
      }
      cost <- flag("cost")
      activation <- flag("activation")
    } yield Scope(version, tiers, cost, kinds, activation)
  }

  def read(runnerJson: Path): Either[String, Scope] =
    Try(new String(Files.readAllBytes(runnerJson), "UTF-8")).toEither.left.map(e => s"$runnerJson: $e")
      .flatMap(t => io.circe.parser.parse(t).left.map(e => s"$runnerJson: $e")).flatMap(parse)

  /** The SANTA commit graded against: HEAD, when `santa` is the top of its git checkout by real path; else None. */
  def santaCommit(santa: Path): Option[String] = {
    def git(args: String*): Option[String] = Try(Seq("git", "-C", santa.toString) ++ args).toOption.flatMap { cmd =>
      val out = new StringBuilder
      val code = Try(cmd.!(ProcessLogger(s => out.append(s), _ => ()))).getOrElse(1)
      if (code == 0) Some(out.toString.trim) else None
    }
    for {
      top <- git("rev-parse", "--show-toplevel")
      real <- Try(Paths.get(top).toRealPath()).toOption
      mine <- Try(santa.toRealPath()).toOption
      if real == mine
      head <- git("rev-parse", "HEAD")
    } yield head
  }
}
