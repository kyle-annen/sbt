/* sbt -- Simple Build Tool
 * Copyright 2011 Mark Harrah
 */
package sbt

import java.net.URI

import sbt.internal.util.{ AttributeKey, AttributeMap, Dag }

import sbt.io.IO

final case class Scope(project: ScopeAxis[Reference],
                       config: ScopeAxis[ConfigKey],
                       task: ScopeAxis[AttributeKey[_]],
                       extra: ScopeAxis[AttributeMap]) {
  def in(project: Reference, config: ConfigKey): Scope =
    copy(project = Select(project), config = Select(config))
  def in(config: ConfigKey, task: AttributeKey[_]): Scope =
    copy(config = Select(config), task = Select(task))
  def in(project: Reference, task: AttributeKey[_]): Scope =
    copy(project = Select(project), task = Select(task))
  def in(project: Reference, config: ConfigKey, task: AttributeKey[_]): Scope =
    copy(project = Select(project), config = Select(config), task = Select(task))
  def in(project: Reference): Scope = copy(project = Select(project))
  def in(config: ConfigKey): Scope = copy(config = Select(config))
  def in(task: AttributeKey[_]): Scope = copy(task = Select(task))
}
object Scope {
  val ThisScope: Scope = Scope(This, This, This, This)
  val Global: Scope = Scope(Zero, Zero, Zero, Zero)
  val GlobalScope: Scope = Global

  def resolveScope(thisScope: Scope, current: URI, rootProject: URI => String): Scope => Scope =
    resolveProject(current, rootProject) compose replaceThis(thisScope) compose subThisProject

  def resolveBuildScope(thisScope: Scope, current: URI): Scope => Scope =
    buildResolve(current) compose replaceThis(thisScope) compose subThisProject

  def replaceThis(thisScope: Scope): Scope => Scope =
    (scope: Scope) =>
      Scope(
        subThis(thisScope.project, scope.project),
        subThis(thisScope.config, scope.config),
        subThis(thisScope.task, scope.task),
        subThis(thisScope.extra, scope.extra)
    )

  def subThis[T](sub: ScopeAxis[T], into: ScopeAxis[T]): ScopeAxis[T] =
    if (into == This) sub else into

  /**
   * `Select(ThisProject)` cannot be resolved by [[resolveProject]] (it doesn't know what to replace it with), so we
   * perform this transformation so that [[replaceThis]] picks it up.
   */
  def subThisProject: Scope => Scope = {
    case s @ Scope(Select(ThisProject), _, _, _) => s.copy(project = This)
    case s                                       => s
  }

  def fillTaskAxis(scope: Scope, key: AttributeKey[_]): Scope =
    scope.task match {
      case _: Select[_] => scope
      case _            => scope.copy(task = Select(key))
    }

  def mapReference(f: Reference => Reference): Scope => Scope = {
    case Scope(Select(ref), a, b, c) => Scope(Select(f(ref)), a, b, c)
    case x                           => x
  }
  def resolveProject(uri: URI, rootProject: URI => String): Scope => Scope =
    mapReference(ref => resolveReference(uri, rootProject, ref))
  def buildResolve(uri: URI): Scope => Scope =
    mapReference(ref => resolveBuildOnly(uri, ref))

  def resolveBuildOnly(current: URI, ref: Reference): Reference =
    ref match {
      case br: BuildReference   => resolveBuild(current, br)
      case pr: ProjectReference => resolveProjectBuild(current, pr)
    }
  def resolveBuild(current: URI, ref: BuildReference): BuildReference =
    ref match {
      case ThisBuild     => BuildRef(current)
      case BuildRef(uri) => BuildRef(resolveBuild(current, uri))
    }
  def resolveProjectBuild(current: URI, ref: ProjectReference): ProjectReference =
    ref match {
      case LocalRootProject    => RootProject(current)
      case LocalProject(id)    => ProjectRef(current, id)
      case RootProject(uri)    => RootProject(resolveBuild(current, uri))
      case ProjectRef(uri, id) => ProjectRef(resolveBuild(current, uri), id)
      case ThisProject =>
        RootProject(current) // Is this right? It was an inexhaustive match before..
    }
  def resolveBuild(current: URI, uri: URI): URI =
    if (!uri.isAbsolute && current.isOpaque && uri.getSchemeSpecificPart == ".")
      current // this handles the shortcut of referring to the current build using "."
    else
      IO.directoryURI(current resolve uri)

  def resolveReference(current: URI,
                       rootProject: URI => String,
                       ref: Reference): ResolvedReference =
    ref match {
      case br: BuildReference   => resolveBuildRef(current, br)
      case pr: ProjectReference => resolveProjectRef(current, rootProject, pr)
    }

  def resolveProjectRef(current: URI,
                        rootProject: URI => String,
                        ref: ProjectReference): ProjectRef =
    ref match {
      case LocalRootProject => ProjectRef(current, rootProject(current))
      case LocalProject(id) => ProjectRef(current, id)
      case RootProject(uri) =>
        val res = resolveBuild(current, uri); ProjectRef(res, rootProject(res))
      case ProjectRef(uri, id) => ProjectRef(resolveBuild(current, uri), id)
      case ThisProject =>
        ProjectRef(current, rootProject(current)) // Is this right? It was an inexhaustive match before..
    }
  def resolveBuildRef(current: URI, ref: BuildReference): BuildRef =
    ref match {
      case ThisBuild     => BuildRef(current)
      case BuildRef(uri) => BuildRef(resolveBuild(current, uri))
    }

  def display(config: ConfigKey): String = config.name + ":"

  def display(scope: Scope, sep: String): String =
    displayMasked(scope, sep, showProject, ScopeMask())

  def displayMasked(scope: Scope, sep: String, mask: ScopeMask): String =
    displayMasked(scope, sep, showProject, mask)

  def display(scope: Scope, sep: String, showProject: Reference => String): String =
    displayMasked(scope, sep, showProject, ScopeMask())

  def displayMasked(
      scope: Scope,
      sep: String,
      showProject: Reference => String,
      mask: ScopeMask
  ): String = {
    import scope.{ project, config, task, extra }
    val configPrefix = config.foldStrict(display, "*:", ".:")
    val taskPrefix = task.foldStrict(_.label + "::", "", ".::")
    val extras = extra.foldStrict(_.entries.map(_.toString).toList, Nil, Nil)
    val postfix = if (extras.isEmpty) "" else extras.mkString("(", ", ", ")")
    mask.concatShow(projectPrefix(project, showProject), configPrefix, taskPrefix, sep, postfix)
  }

  def equal(a: Scope, b: Scope, mask: ScopeMask): Boolean =
    (!mask.project || a.project == b.project) &&
      (!mask.config || a.config == b.config) &&
      (!mask.task || a.task == b.task) &&
      (!mask.extra || a.extra == b.extra)

  def projectPrefix(
      project: ScopeAxis[Reference],
      show: Reference => String = showProject
  ): String =
    project.foldStrict(show, "*/", "./")

  def showProject = (ref: Reference) => Reference.display(ref) + "/"

  def transformTaskName(s: String) = {
    val parts = s.split("-+")
    (parts.take(1) ++ parts.drop(1).map(_.capitalize)).mkString
  }

  // *Inherit functions should be immediate delegates and not include argument itself.  Transitivity will be provided by this method
  def delegates[Proj](
      refs: Seq[(ProjectRef, Proj)],
      configurations: Proj => Seq[ConfigKey],
      resolve: Reference => ResolvedReference,
      rootProject: URI => String,
      projectInherit: ProjectRef => Seq[ProjectRef],
      configInherit: (ResolvedReference, ConfigKey) => Seq[ConfigKey],
      taskInherit: AttributeKey[_] => Seq[AttributeKey[_]],
      extraInherit: (ResolvedReference, AttributeMap) => Seq[AttributeMap]
  ): Scope => Seq[Scope] = {
    val index = delegates(refs, configurations, projectInherit, configInherit)
    scope =>
      indexedDelegates(resolve, index, rootProject, taskInherit, extraInherit)(scope)
  }

  def indexedDelegates(
      resolve: Reference => ResolvedReference,
      index: DelegateIndex,
      rootProject: URI => String,
      taskInherit: AttributeKey[_] => Seq[AttributeKey[_]],
      extraInherit: (ResolvedReference, AttributeMap) => Seq[AttributeMap]
  )(rawScope: Scope): Seq[Scope] = {
    val scope = Scope.replaceThis(GlobalScope)(rawScope)

    def nonProjectScopes(resolvedProj: ResolvedReference)(px: ScopeAxis[ResolvedReference]) = {
      val p = px.toOption getOrElse resolvedProj
      val configProj = p match {
        case pr: ProjectRef => pr; case br: BuildRef => ProjectRef(br.build, rootProject(br.build))
      }
      val cLin = scope.config match {
        case Select(conf) => index.config(configProj, conf); case _ => withZeroAxis(scope.config)
      }
      val tLin = scope.task match {
        case t @ Select(task) => linearize(t)(taskInherit); case _ => withZeroAxis(scope.task)
      }
      val eLin = withZeroAxis(scope.extra)
      for (c <- cLin; t <- tLin; e <- eLin) yield Scope(px, c, t, e)
    }
    scope.project match {
      case Zero | This => globalProjectDelegates(scope)
      case Select(proj) =>
        val resolvedProj = resolve(proj)
        val projAxes: Seq[ScopeAxis[ResolvedReference]] =
          resolvedProj match {
            case pr: ProjectRef => index.project(pr)
            case br: BuildRef   => Select(br) :: Zero :: Nil
          }
        projAxes flatMap nonProjectScopes(resolvedProj)
    }
  }

  def withZeroAxis[T](base: ScopeAxis[T]): Seq[ScopeAxis[T]] =
    if (base.isSelect) base :: Zero :: Nil
    else Zero :: Nil
  def withGlobalScope(base: Scope): Seq[Scope] =
    if (base == GlobalScope) GlobalScope :: Nil else base :: GlobalScope :: Nil
  def withRawBuilds(ps: Seq[ScopeAxis[ProjectRef]]): Seq[ScopeAxis[ResolvedReference]] =
    ps ++ (ps flatMap rawBuild).distinct :+ Zero

  def rawBuild(ps: ScopeAxis[ProjectRef]): Seq[ScopeAxis[BuildRef]] = ps match {
    case Select(ref) => Select(BuildRef(ref.build)) :: Nil; case _ => Nil
  }

  def delegates[Proj](
      refs: Seq[(ProjectRef, Proj)],
      configurations: Proj => Seq[ConfigKey],
      projectInherit: ProjectRef => Seq[ProjectRef],
      configInherit: (ResolvedReference, ConfigKey) => Seq[ConfigKey]
  ): DelegateIndex = {
    val pDelegates = refs map {
      case (ref, project) =>
        (ref, delegateIndex(ref, configurations(project))(projectInherit, configInherit))
    } toMap;
    new DelegateIndex0(pDelegates)
  }
  private[this] def delegateIndex(ref: ProjectRef, confs: Seq[ConfigKey])(
      projectInherit: ProjectRef => Seq[ProjectRef],
      configInherit: (ResolvedReference, ConfigKey) => Seq[ConfigKey]): ProjectDelegates = {
    val refDelegates = withRawBuilds(linearize(Select(ref), false)(projectInherit))
    val configs = confs map { c =>
      axisDelegates(configInherit, ref, c)
    }
    new ProjectDelegates(ref, refDelegates, configs.toMap)
  }
  def axisDelegates[T](direct: (ResolvedReference, T) => Seq[T],
                       ref: ResolvedReference,
                       init: T): (T, Seq[ScopeAxis[T]]) =
    (init, linearize(Select(init))(direct(ref, _)))

  def linearize[T](axis: ScopeAxis[T], appendZero: Boolean = true)(
      inherit: T => Seq[T]): Seq[ScopeAxis[T]] =
    axis match {
      case Select(x)   => topologicalSort[T](x, appendZero)(inherit)
      case Zero | This => if (appendZero) Zero :: Nil else Nil
    }

  def topologicalSort[T](node: T, appendZero: Boolean)(
      dependencies: T => Seq[T]): Seq[ScopeAxis[T]] = {
    val o = Dag.topologicalSortUnchecked(node)(dependencies).map(Select.apply)
    if (appendZero) o ::: Zero :: Nil
    else o
  }
  def globalProjectDelegates(scope: Scope): Seq[Scope] =
    if (scope == GlobalScope)
      GlobalScope :: Nil
    else
      for {
        c <- withZeroAxis(scope.config)
        t <- withZeroAxis(scope.task)
        e <- withZeroAxis(scope.extra)
      } yield Scope(Zero, c, t, e)
}
