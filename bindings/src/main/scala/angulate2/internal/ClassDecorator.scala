//     Project: angulate2
// Description: Macro base for classes decorated with TypeScript annotations

// Copyright (c) 2016 Johannes.Kastner <jokade@karchedon.de>
//               Distributed under the MIT License (see included LICENSE file)
package angulate2.internal

import angulate2.ext.ClassMode
import de.surfice.smacrotools.{MacroAnnotationHandler}

import scala.language.reflectiveCalls

abstract class ClassDecorator extends MacroAnnotationHandler
  with AngulateWhiteboxMacroTools {

  import c.universe._

  override def supportsClasses: Boolean = true
  override def supportsTraits: Boolean = false
  override def supportsObjects: Boolean = false
  override def createCompanion: Boolean = true

  type Metadata = Map[String,String]
  type MainAnnotationParams = Map[String,Tree]

  case class ClassDecoratorData(objName: String,
                                decorators: Seq[Tree],
                                metadata: Metadata,
                                userDefinedCompanion: Boolean,
                                classMode: ClassMode.Value,
                                annotParams: MainAnnotationParams,
                                sjsxStatic: Seq[(Int,String)] = Nil
                               ) {
    def addSjsxStatic(js: (Int,String)*): ClassDecoratorData =
      copy(sjsxStatic = sjsxStatic ++ js)
    def jsAccessor: String = s"$exports.$objName"
  }
  object ClassDecoratorData {
    def apply(data: Data): ClassDecoratorData = data("decoratorData").asInstanceOf[ClassDecoratorData]
    def update(data: Data, cdd: ClassDecoratorData): Data = data + ("decoratorData" -> cdd)
    def addSjsxStatic(data: Data, js: Seq[(Int,String)]): Data = {
      val cdd = ClassDecoratorData(data)
      update(data,cdd.copy(sjsxStatic = cdd.sjsxStatic++js))
    }
    def updAnnotParam(data: Data, key: String, value: Tree): Data = {
      val cdd = ClassDecoratorData(data)
      update(data,cdd.copy(annotParams = cdd.annotParams + (key->value)))
    }
  }

  // Prefix for accessing the Scala module's exports object from within the sjsx module
  protected val exports = "s"


  def mainAnnotationObject: Tree

  /** Names of the parameters in the last argument list of the main annotation
   * (in the correct order).
   */
  def annotationParamNames: Seq[String]

  /** If the macro annotation has two argument lists,
   * this sequence contains the names of the parameters in the first argument list.
   */
  def firstArglistParamNames: Seq[String] = Nil


  override def analyze: Analysis = super.analyze andThen {
    case (origParts:ClassParts,data) =>
      val decor = initClassDecoratorData(origParts,data)
      (origParts,decor)
    case default => default
  }


  override def transform: Transformation = commonTransform andThen {
    // modify annotations for the class carrying the macro annotation
    case cls: ClassTransformData =>
      val classDecoratorData = ClassDecoratorData(cls.data)
      // assemble JS for class decoration
      val js = (classDecoratorData.sjsxStatic :+ genClassDecoration(cls.modParts,classDecoratorData))
        .sortBy(_._1)
        .map(_._2)
        .mkString("","\n","\n")
      cls.addAnnotations(
        q"new scalajs.js.annotation.JSExportTopLevel(${cls.modParts.fullName})",
        q"new sjsx.SJSXStatic(1000,$js)"
      )
    // update the companion object
    case obj: ObjectTransformData =>
      val classDecoratorData = ClassDecoratorData(obj.data)
      import classDecoratorData._

      val mainAnnotationParams = annotParams.map {
        case (name,value) => q"${Ident(TermName(name))} = $value"
      }
      val mainAnnotation =
//        if(mainAnnotationParams.isEmpty)
//          q"$mainAnnotationObject()"
//        else
          q"$mainAnnotationObject( _annotation )"

      obj
        .addAnnotations( q"new scalajs.js.annotation.JSExportTopLevel($objName)" )
        .addStatements(
          q"""private var _annotationData: scalajs.js.Object = null""",
          q"""def _annotation = {
                if(_annotationData == null)
                  _annotationData = scalajs.js.Dynamic.literal(..$mainAnnotationParams)
                _annotationData
              }
            """,
          q"""def _decorators = scalajs.js.Array( $mainAnnotation, ..$decorators )""" )
    case default => default
  }

  private val typeSeqJsObject = Seq(tq"scalajs.js.Object")

  private val jsObjectType = weakTypeOf[scalajs.js.Object]

  private def commonTransform: Transformation = { tdata =>
    val classDecoratorData = ClassDecoratorData(tdata.data)
    import classDecoratorData._
    tdata
      .addAnnotations( classModeAnnotation(classMode) )
      .updParents(
        if(classMode==ClassMode.JS) tdata.modParts.parents match {
          case Nil => typeSeqJsObject
          case Seq(x) if x.toString == "scala.AnyRef" => typeSeqJsObject
          case parents => parents.map(c.typecheck(_,c.TYPEmode)) map {
            case x if x.tpe <:< jsObjectType => x
            case x => tq"${x.tpe.typeSymbol.companion}.JS[..${x.tpe.typeArgs}]"
          }
        }
        else tdata.modParts.parents )
  }

  private def classModeAnnotation(classMode: ClassMode.Value) = classMode match {
    case ClassMode.Scala => q"new scalajs.js.annotation.JSExportAll"
    case ClassMode.JS => q"new scalajs.js.annotation.ScalaJSDefined"
  }


  private def mainAnnotationParams(parts: ClassParts, annotationParamNames: Seq[String]): MainAnnotationParams = {
    extractAnnotationParameters(c.prefix.tree, annotationParamNames, firstArglistParamNames).collect {
      case (name,Some(value)) => (name,value)
    }
  }

  /**
   * Assemble initial ClassDecoratorData
   */
  private def initClassDecoratorData(parts: ClassParts, data: Data): Data = {
    import parts._

    val objName = fullName + "_"

    val diTypes = getInjectionDependencies(params) map {
      case ScalaDependency(fqn) => s"$exports.$fqn"
      case RequireDependency(module,name) => s"require('$module').$name"
    }

    // determine class mode
    // (default: Scala; with @classModeJS => JS)
    val classMode =
      if(findAnnotation(modifiers.annotations,"classModeScala").isDefined) ClassMode.Scala
      else ClassMode.JS

    val metadata: Metadata =
      if(diTypes.isEmpty) Map.empty[String,String]
      else Map("design:paramtypes"->diTypes.mkString("[",",","]"))

    data + ("decoratorData"->ClassDecoratorData(
      objName,
      Nil,
      metadata,
      companion.isDefined,
      classMode,
      mainAnnotationParams(parts,annotationParamNames)
    ))
  }

  /**
   * Generate the JS string used to decorate the class.
   *
   * @param parts
   * @param data
   * @return
   */
  private def genClassDecoration(parts: ClassParts, data: ClassDecoratorData): (Int,String) = {
    import data._
    import parts._

    val decoration =
      if(metadata.isEmpty) s"$exports.$objName._decorators"
      else s"$exports.$objName._decorators.concat(" + metadata.map(p => s"__metadata('${p._1}',${p._2})").mkString("[",",","]") + ")"
    (1000,s"$exports.$fullName = __decorate($decoration,$exports.$fullName);")
  }

  case class DecorationMetadata(key: String, value: String)
  object DecorationMetadata {
//    def designType(tree: Tree): DecorationMetadata = {
//      DecorationMetadata("design:type","String")
//    }
  }

  case class MethodDecoration(decorator: String, prototype: String, method: String, metadata: Iterable[DecorationMetadata]) {
    def toJS: String = {
      val metajs = metadata.map(s => s"__metadata('${s.key}',${s.value})").mkString(",")
      s"__decorate([$decorator,$metajs],$exports.$prototype.prototype,'$method',null);"
    }
  }
}

