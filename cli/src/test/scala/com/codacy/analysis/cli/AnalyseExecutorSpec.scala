package com.codacy.analysis.cli

import better.files.File
import com.codacy.analysis.cli.command.analyse.AnalyseExecutor
import com.codacy.analysis.cli.configuration.CLIProperties
import com.codacy.analysis.cli.configuration.CLIProperties.AnalysisProperties
import com.codacy.analysis.cli.configuration.CLIProperties.AnalysisProperties.Tool.IssuesToolConfiguration
import com.codacy.analysis.cli.formatter.{Formatter, Json}
import com.codacy.analysis.core.analysis.Analyser
import com.codacy.analysis.core.clients.api._
import com.codacy.analysis.core.files.FileCollector
import com.codacy.analysis.core.model.{Issue, Result, ToolResult}
import com.codacy.analysis.core.utils.TestUtils._
import io.circe.generic.auto._
import io.circe.parser
import org.specs2.control.NoLanguageFeatures
import org.specs2.matcher.FutureMatchers
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification

import scala.concurrent.duration._
import scala.util.Try

class AnalyseExecutorSpec extends Specification with NoLanguageFeatures with Mockito with FutureMatchers {

  "AnalyseExecutor" should {

    val pyLintPatternsInternalIds = Set("PyLint_C0111", "PyLint_E1101")
    val pathToIgnore = "lib/improver/tests/"

    s"""|analyse a python project with pylint, using a remote project configuration retrieved with a project token
        | that ignores the files that start with the path $pathToIgnore
        | and considers just patterns ${pyLintPatternsInternalIds.mkString(", ")}""".stripMargin in {
      val commitUuid = "9232dbdcae98b19412c8dd98c49da8c391612bfa"
      withClonedRepo("git://github.com/qamine-test/improver.git", commitUuid) { (file, directory) =>
        val toolPatterns = pyLintPatternsInternalIds.map { patternId =>
          new IssuesToolConfiguration.Pattern(patternId, Set.empty)
        }

        val properties = analysisProperties(
          directory,
          file,
          Option("pylint"),
          Set(
            new IssuesToolConfiguration(
              uuid = "34225275-f79e-4b85-8126-c7512c987c0d",
              enabled = true,
              notEdited = false,
              toolPatterns)),
          Set(FilePath(pathToIgnore)))

        runAnalyseExecutor(Analyser.defaultAnalyser.name, properties)

        val result = for {
          responseJson <- parser.parse(file.contentAsString)
          response <- responseJson.as[Set[ToolResult]]
        } yield response

        result must beRight
        result must beLike {
          case Right(response: Set[ToolResult]) =>
            response.size must beGreaterThan(0)

            response.forall {
              case i: Issue => !i.filename.startsWith(pathToIgnore)
              case _        => true
            } must beTrue

            response.forall {
              case i: Issue => pyLintPatternsInternalIds.contains(i.patternId.value)
              case _        => true
            } must beTrue
        }
      }
    }

    val esLintPatternsInternalIds = Set("ESLint_semi", "ESLint_no-undef", "ESLint_indent", "ESLint_no-empty")

    s"""|analyse a javascript project with eslint, using a remote project configuration retrieved with an api token
        | that considers just patterns ${esLintPatternsInternalIds.mkString(", ")}""".stripMargin in {
      val commitUuid = "9232dbdcae98b19412c8dd98c49da8c391612bfa"
      withClonedRepo("git://github.com/qamine-test/Monogatari.git", commitUuid) { (file, directory) =>
        val toolPatterns = esLintPatternsInternalIds.map { patternId =>
          new IssuesToolConfiguration.Pattern(patternId, Set.empty)
        }
        val properties = analysisProperties(
          directory,
          file,
          Option("eslint"),
          Set(
            new IssuesToolConfiguration(
              uuid = "cf05f3aa-fd23-4586-8cce-5368917ec3e5",
              enabled = true,
              notEdited = false,
              toolPatterns)),
          Set.empty)

        runAnalyseExecutor(Analyser.defaultAnalyser.name, properties)

        val result = for {
          responseJson <- parser.parse(file.contentAsString)
          response <- responseJson.as[Set[Result]]
        } yield response

        result must beRight
        result must beLike {
          case Right(response: Set[Result]) =>
            response.size must beGreaterThan(0)

            response.forall {
              case i: Issue => esLintPatternsInternalIds.contains(i.patternId.value)
              case _        => true
            } must beTrue
        }
      }
    }

    val cssLintPatternsInternalIds = Set("CSSLint_important")

    "analyse a javascript and css project" in {
      val commitUuid = "9232dbdcae98b19412c8dd98c49da8c391612bfa"
      withClonedRepo("git://github.com/qamine-test/Monogatari.git", commitUuid) { (file, directory) =>
        val properties = analysisProperties(
          directory,
          file,
          Option.empty,
          Set(
            new IssuesToolConfiguration(
              uuid = "cf05f3aa-fd23-4586-8cce-5368917ec3e5",
              enabled = true,
              notEdited = false,
              patterns = esLintPatternsInternalIds.map { patternId =>
                new IssuesToolConfiguration.Pattern(patternId, Set.empty)
              }),
            new IssuesToolConfiguration(
              uuid = "997201eb-0907-4823-87c0-a8f7703531e7",
              enabled = true,
              notEdited = true,
              patterns = cssLintPatternsInternalIds.map { patternId =>
                new IssuesToolConfiguration.Pattern(patternId, Set.empty)
              })),
          Set.empty)

        runAnalyseExecutor(Analyser.defaultAnalyser.name, properties)

        val result = for {
          responseJson <- parser.parse(file.contentAsString)
          response <- responseJson.as[Set[Result]]
        } yield response

        result must beRight
        result must beLike {
          case Right(response: Set[Result]) =>
            response.size must beGreaterThan(0)

            response.collect {
              case i: Issue => i.patternId.value
            } must containAllOf((esLintPatternsInternalIds ++ cssLintPatternsInternalIds).toSeq)
        }
      }
    }
  }

  private def runAnalyseExecutor(analyserName: String, analysisProperties: AnalysisProperties) = {
    val formatter: Formatter = Formatter(analysisProperties.output.format, analysisProperties.output.file)
    val analyser: Analyser[Try] = Analyser(analyserName)
    val fileCollector: FileCollector[Try] = FileCollector.defaultCollector()

    new AnalyseExecutor(formatter, analyser, fileCollector, analysisProperties).run() must beRight
  }

  private def analysisProperties(directory: File,
                                 outputFile: File,
                                 tool: Option[String],
                                 toolConfigs: Set[IssuesToolConfiguration],
                                 ignoredPaths: Set[FilePath]) = {
    val fileExclusions = new AnalysisProperties.FileExclusionRules(
      Some(Set.empty),
      ignoredPaths,
      new AnalysisProperties.FileExclusionRules.ExcludePaths(Set.empty, Map.empty),
      Map.empty)

    val toolProperties = new CLIProperties.AnalysisProperties.Tool(
      Option(15.minutes),
      allowNetwork = false,
      Right(toolConfigs),
      Option.empty,
      Map.empty)
    new AnalysisProperties(
      directory,
      new AnalysisProperties.Output(Json.name, Option(outputFile)),
      tool,
      Option.empty,
      forceFilePermissions = false,
      fileExclusions,
      toolProperties)
  }
}
