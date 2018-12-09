//     Project: angulate2
//      Module: @angular/core/ng_module (v2.2.1)
// Description: Façade traits for @angular/core/ng_module

// Copyright (c) 2016 Johannes.Kastner <jokade@karchedon.de>
//               Distributed under the MIT License (see included LICENSE file)
package angulate2.core

import angulate2.internal.ClassDecorator

import scala.annotation.{StaticAnnotation, compileTimeOnly}
import scala.reflect.macros.whitebox
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

/**
 * A wrapper around a module that also includes the providers.
 *
 * @note stable
 */
@js.native
trait ModuleWithProviders extends js.Object {
  def ngModule: js.Dynamic = js.native
  def providers: js.UndefOr[js.Array[Provider]] = js.native
}

@js.native
trait ModuleWithComponentFactories[T] extends js.Object {
  def ngModuleFactory: NgModuleFactory[T] = js.native
  def componentFactories: js.Array[ComponentFactory[js.Dynamic]] = js.native
}

@js.native
trait NgModuleFactory[T] extends js.Object {
  def moduleType: Type[T] = js.native
  def create(parentInjector: Injector): NgModuleRef[T] = js.native
}

@js.native
trait NgModuleRef[T] extends js.Object {
  def injector: Injector = js.native
  def componentFactoryResolver: js.Dynamic = js.native
  def instance: T = js.native
}

@js.native
@JSImport("@angular/core","NgModule")
object NgModuleFacade extends js.Object {
  def apply() : js.Object = js.native
  def apply(options: js.Object) : js.Object = js.native
}

import scala.language.experimental.macros

// NOTE: keep the constructor parameter list and Component.Macro.annotationParamNames in sync!
@compileTimeOnly("enable macro paradise to expand macro annotations")
class NgModule(providers: js.Array[js.Any] = null,
               declarations: js.Array[js.Any] = null,
               imports: js.Array[js.Any] = null,
               exports: js.Array[js.Any] = null,
               entryComponents: js.Array[js.Any] = null,
               bootstrap: js.Array[js.Any] = null,
               schemas: js.Array[js.Any] = null,
               id: String = null) extends StaticAnnotation {
  def macroTransform(annottees: Any*): Any = macro NgModule.Macro.impl
}

object NgModule {
  private[angulate2] class Macro(val c: whitebox.Context) extends ClassDecorator {
    import c.universe._

    val annotationParamNames = Seq(
      "providers",
      "declarations",
      "imports",
      "exports",
      "entryComponents",
      "bootstrap",
      "schemas",
      "id"
    )

    override val annotationName: String = "NgModule"

    override def mainAnnotationObject = q"angulate2.core.NgModuleFacade"
  }

}
