/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.kotlinpoet

import com.google.common.collect.ImmutableMap
import com.google.common.truth.Truth.assertThat
import com.google.testing.compile.CompilationRule
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito
import java.io.IOException
import java.io.Serializable
import java.math.BigDecimal
import java.util.AbstractSet
import java.util.Arrays
import java.util.Collections
import java.util.Comparator
import java.util.EventListener
import java.util.Locale
import java.util.Random
import java.util.concurrent.Callable
import javax.lang.model.element.Element
import javax.lang.model.element.Modifier
import javax.lang.model.element.TypeElement
import kotlin.reflect.KClass

class TypeSpecTest {
  private val tacosPackage = "com.squareup.tacos"

  @Rule @JvmField val compilation = CompilationRule()

  private fun getElement(clazz: Class<*>): TypeElement {
    return compilation.elements.getTypeElement(clazz.canonicalName)
  }

  private fun getElement(clazz: KClass<*>): TypeElement {
    return getElement(clazz.java)
  }

  private val isJava8: Boolean
    get() = Util.DEFAULT != null

  @Test fun basic() {
    val taco = TypeSpec.classBuilder("Taco")
        .addFun(FunSpec.builder("toString")
            .addAnnotation(Override::class)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .returns(String::class)
            .addCode("return %S;\n", "taco")
            .build())
        .build()
    assertThat(toString(taco)).isEqualTo("""
        |package com.squareup.tacos
        |
        |import java.lang.Override
        |import java.lang.String
        |
        |class Taco {
        |  @Override
        |  public final fun toString(): String {
        |    return "taco";
        |  }
        |}
        |""".trimMargin())
    assertEquals(-708668397, taco.hashCode().toLong()) // Update expected number if source changes.
  }

  @Test fun interestingTypes() {
    val listOfAny = ParameterizedTypeName.get(
        ClassName.get(List::class), WildcardTypeName.subtypeOf(ANY))
    val listOfExtends = ParameterizedTypeName.get(
        ClassName.get(List::class), WildcardTypeName.subtypeOf(Serializable::class))
    val listOfSuper = ParameterizedTypeName.get(ClassName.get(List::class),
        WildcardTypeName.supertypeOf(String::class))
    val taco = TypeSpec.classBuilder("Taco")
        .addProperty(listOfAny, "star")
        .addProperty(listOfExtends, "outSerializable")
        .addProperty(listOfSuper, "inString")
        .build()
    assertThat(toString(taco)).isEqualTo("""
        |package com.squareup.tacos
        |
        |import java.io.Serializable
        |import java.lang.String
        |import java.util.List
        |
        |class Taco {
        |  star: List<*>;
        |
        |  outSerializable: List<out Serializable>;
        |
        |  inString: List<in String>;
        |}
        |""".trimMargin())
  }

  @Test fun anonymousInnerClass() {
    val foo = ClassName.get(tacosPackage, "Foo")
    val bar = ClassName.get(tacosPackage, "Bar")
    val thingThang = ClassName.get(tacosPackage, "Thing", "Thang")
    val thingThangOfFooBar = ParameterizedTypeName.get(thingThang, foo, bar)
    val thung = ClassName.get(tacosPackage, "Thung")
    val simpleThung = ClassName.get(tacosPackage, "SimpleThung")
    val thungOfSuperBar = ParameterizedTypeName.get(thung, WildcardTypeName.supertypeOf(bar))
    val thungOfSuperFoo = ParameterizedTypeName.get(thung, WildcardTypeName.supertypeOf(foo))
    val simpleThungOfBar = ParameterizedTypeName.get(simpleThung, bar)

    val thungParameter = ParameterSpec.builder(thungOfSuperFoo, "thung")
        .addModifiers(Modifier.FINAL)
        .build()
    val aSimpleThung = TypeSpec.anonymousClassBuilder("%N", thungParameter)
        .superclass(simpleThungOfBar)
        .addFun(FunSpec.builder("doSomething")
            .addAnnotation(Override::class)
            .addModifiers(Modifier.PUBLIC)
            .addParameter(bar, "bar")
            .addCode("/* code snippets */\n")
            .build())
        .build()
    val aThingThang = TypeSpec.anonymousClassBuilder("")
        .superclass(thingThangOfFooBar)
        .addFun(FunSpec.builder("call")
            .addAnnotation(Override::class)
            .addModifiers(Modifier.PUBLIC)
            .returns(thungOfSuperBar)
            .addParameter(thungParameter)
            .addCode("return %L;\n", aSimpleThung)
            .build())
        .build()
    val taco = TypeSpec.classBuilder("Taco")
        .addProperty(PropertySpec.builder(thingThangOfFooBar, "NAME")
            .addModifiers(Modifier.STATIC, Modifier.FINAL, Modifier.FINAL)
            .initializer("%L", aThingThang)
            .build())
        .build()

    assertThat(toString(taco)).isEqualTo("""
        |package com.squareup.tacos
        |
        |import java.lang.Override
        |
        |class Taco {
        |  static final NAME: Thing.Thang<Foo, Bar> = new Thing.Thang<Foo, Bar>() {
        |    @Override
        |    public fun call(final thung: Thung<in Foo>): Thung<in Bar> {
        |      return new SimpleThung<Bar>(thung) {
        |        @Override
        |        public fun doSomething(bar: Bar) {
        |          /* code snippets */
        |        }
        |      };
        |    }
        |  };
        |}
        |""".trimMargin())
  }

  @Test fun annotatedParameters() {
    val service = TypeSpec.classBuilder("Foo")
        .addFun(FunSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(Long::class.javaPrimitiveType!!, "id")
            .addParameter(ParameterSpec.builder(String::class, "one")
                .addAnnotation(ClassName.get(tacosPackage, "Ping"))
                .build())
            .addParameter(ParameterSpec.builder(String::class, "two")
                .addAnnotation(ClassName.get(tacosPackage, "Ping"))
                .build())
            .addParameter(ParameterSpec.builder(String::class, "three")
                .addAnnotation(AnnotationSpec.builder(ClassName.get(tacosPackage, "Pong"))
                    .addMember("value", "%S", "pong")
                    .build())
                .build())
            .addParameter(ParameterSpec.builder(String::class, "four")
                .addAnnotation(ClassName.get(tacosPackage, "Ping"))
                .build())
            .addCode("/* code snippets */\n")
            .build())
        .build()

    assertThat(toString(service)).isEqualTo("""
        |package com.squareup.tacos
        |
        |import java.lang.String
        |import kotlin.Long
        |
        |class Foo {
        |  public constructor(id: Long, @Ping one: String, @Ping two: String, @Pong("pong") three: String,
        |      @Ping four: String) {
        |    /* code snippets */
        |  }
        |}
        |""".trimMargin())
  }

  /**
   * We had a bug where annotations were preventing us from doing the right thing when resolving
   * imports. https://github.com/square/javapoet/issues/422
   */
  @Test fun annotationsAndJavaLangTypes() {
    val freeRange = ClassName.get("javax.annotation", "FreeRange")
    val taco = TypeSpec.classBuilder("EthicalTaco")
        .addProperty(ClassName.get(String::class)
            .annotated(AnnotationSpec.builder(freeRange).build()), "meat")
        .build()

    assertThat(toString(taco)).isEqualTo("""
        |package com.squareup.tacos
        |
        |import java.lang.String
        |import javax.annotation.FreeRange
        |
        |class EthicalTaco {
        |  meat: @FreeRange String;
        |}
        |""".trimMargin())
  }

  @Test fun retrofitStyleInterface() {
    val observable = ClassName.get(tacosPackage, "Observable")
    val fooBar = ClassName.get(tacosPackage, "FooBar")
    val thing = ClassName.get(tacosPackage, "Thing")
    val things = ClassName.get(tacosPackage, "Things")
    val map = ClassName.get("java.util", "Map")
    val string = ClassName.get("java.lang", "String")
    val headers = ClassName.get(tacosPackage, "Headers")
    val post = ClassName.get(tacosPackage, "POST")
    val body = ClassName.get(tacosPackage, "Body")
    val queryMap = ClassName.get(tacosPackage, "QueryMap")
    val header = ClassName.get(tacosPackage, "Header")
    val service = TypeSpec.interfaceBuilder("Service")
        .addFun(FunSpec.builder("fooBar")
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .addAnnotation(AnnotationSpec.builder(headers)
                .addMember("value", "%S", "Accept: application/json")
                .addMember("value", "%S", "User-Agent: foobar")
                .build())
            .addAnnotation(AnnotationSpec.builder(post)
                .addMember("value", "%S", "/foo/bar")
                .build())
            .returns(ParameterizedTypeName.get(observable, fooBar))
            .addParameter(ParameterSpec.builder(ParameterizedTypeName.get(things, thing), "things")
                .addAnnotation(body)
                .build())
            .addParameter(ParameterSpec.builder(
                ParameterizedTypeName.get(map, string, string), "query")
                .addAnnotation(AnnotationSpec.builder(queryMap)
                    .addMember("encodeValues", "false")
                    .build())
                .build())
            .addParameter(ParameterSpec.builder(string, "authorization")
                .addAnnotation(AnnotationSpec.builder(header)
                    .addMember("value", "%S", "Authorization")
                    .build())
                .build())
            .build())
        .build()

    assertThat(toString(service)).isEqualTo("""
        |package com.squareup.tacos
        |
        |import java.lang.String
        |import java.util.Map
        |
        |interface Service {
        |  @Headers({
        |      "Accept: application/json",
        |      "User-Agent: foobar"
        |  })
        |  @POST("/foo/bar")
        |  fun fooBar(@Body things: Things<Thing>,
        |      @QueryMap(encodeValues = false) query: Map<String, String>,
        |      @Header("Authorization") authorization: String): Observable<FooBar>;
        |}
        |""".trimMargin())
  }

  @Test fun annotatedProperty() {
    val taco = TypeSpec.classBuilder("Taco")
        .addProperty(PropertySpec.builder(String::class, "thing", Modifier.PRIVATE, Modifier.FINAL)
            .addAnnotation(AnnotationSpec.builder(ClassName.get(tacosPackage, "JsonAdapter"))
                .addMember("value", "%T.class", ClassName.get(tacosPackage, "Foo"))
                .build())
            .build())
        .build()
    assertThat(toString(taco)).isEqualTo("""
        |package com.squareup.tacos
        |
        |import java.lang.String
        |
        |class Taco {
        |  @JsonAdapter(Foo.class)
        |  private final thing: String;
        |}
        |""".trimMargin())
  }

  @Test fun annotatedClass() {
    val someType = ClassName.get(tacosPackage, "SomeType")
    val taco = TypeSpec.classBuilder("Foo")
        .addAnnotation(AnnotationSpec.builder(ClassName.get(tacosPackage, "Something"))
            .addMember("hi", "%T.%N", someType, "PROPERTY")
            .addMember("hey", "%L", 12)
            .addMember("hello", "%S", "goodbye")
            .build())
        .addModifiers(Modifier.PUBLIC)
        .build()
    assertThat(toString(taco)).isEqualTo("""
        |package com.squareup.tacos
        |
        |@Something(
        |    hi = SomeType.PROPERTY,
        |    hey = 12,
        |    hello = "goodbye"
        |)
        |public class Foo {
        |}
        |""".trimMargin())
  }

  @Test fun enumWithSubclassing() {
    val roshambo = TypeSpec.enumBuilder("Roshambo")
        .addModifiers(Modifier.PUBLIC)
        .addEnumConstant("ROCK", TypeSpec.anonymousClassBuilder("")
            .addKdoc("Avalanche!\n")
            .build())
        .addEnumConstant("PAPER", TypeSpec.anonymousClassBuilder("%S", "flat")
            .addFun(FunSpec.builder("toString")
                .addAnnotation(Override::class)
                .addModifiers(Modifier.PUBLIC)
                .returns(String::class)
                .addCode("return %S;\n", "paper airplane!")
                .build())
            .build())
        .addEnumConstant("SCISSORS", TypeSpec.anonymousClassBuilder("%S", "peace sign")
            .build())
        .addProperty(String::class, "handPosition", Modifier.PRIVATE, Modifier.FINAL)
        .addFun(FunSpec.constructorBuilder()
            .addParameter(String::class, "handPosition")
            .addCode("this.handPosition = handPosition;\n")
            .build())
        .addFun(FunSpec.constructorBuilder()
            .addCode("this(%S);\n", "fist")
            .build())
        .build()
    assertThat(toString(roshambo)).isEqualTo("""
        |package com.squareup.tacos
        |
        |import java.lang.Override
        |import java.lang.String
        |
        |public enum Roshambo {
        |  /**
        |   * Avalanche!
        |   */
        |  ROCK,
        |
        |  PAPER("flat") {
        |    @Override
        |    public fun toString(): String {
        |      return "paper airplane!";
        |    }
        |  },
        |
        |  SCISSORS("peace sign");
        |
        |  private final handPosition: String;
        |
        |  constructor(handPosition: String) {
        |    this.handPosition = handPosition;
        |  }
        |
        |  constructor() {
        |    this("fist");
        |  }
        |}
        |""".trimMargin())
  }

  /** https://github.com/square/javapoet/issues/193  */
  @Test fun enumsMayDefineAbstractFunctions() {
    val roshambo = TypeSpec.enumBuilder("Tortilla")
        .addModifiers(Modifier.PUBLIC)
        .addEnumConstant("CORN", TypeSpec.anonymousClassBuilder("")
            .addFun(FunSpec.builder("fold")
                .addAnnotation(Override::class)
                .addModifiers(Modifier.PUBLIC)
                .build())
            .build())
        .addFun(FunSpec.builder("fold")
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .build())
        .build()
    assertThat(toString(roshambo)).isEqualTo("""
        |package com.squareup.tacos
        |
        |import java.lang.Override
        |
        |public enum Tortilla {
        |  CORN {
        |    @Override
        |    public fun fold() {
        |    }
        |  };
        |
        |  public abstract fun fold();
        |}
        |""".trimMargin())
  }

  @Test fun enumConstantsRequired() {
    try {
      TypeSpec.enumBuilder("Roshambo")
          .build()
      fail()
    } catch (expected: IllegalArgumentException) {
    }

  }

  @Test fun onlyEnumsMayHaveEnumConstants() {
    try {
      TypeSpec.classBuilder("Roshambo")
          .addEnumConstant("ROCK")
          .build()
      fail()
    } catch (expected: IllegalStateException) {
    }

  }

  @Test fun enumWithMembersButNoConstructorCall() {
    val roshambo = TypeSpec.enumBuilder("Roshambo")
        .addEnumConstant("SPOCK", TypeSpec.anonymousClassBuilder("")
            .addFun(FunSpec.builder("toString")
                .addAnnotation(Override::class)
                .addModifiers(Modifier.PUBLIC)
                .returns(String::class)
                .addCode("return %S;\n", "west side")
                .build())
            .build())
        .build()
    assertThat(toString(roshambo)).isEqualTo("""
        |package com.squareup.tacos
        |
        |import java.lang.Override
        |import java.lang.String
        |
        |enum Roshambo {
        |  SPOCK {
        |    @Override
        |    public fun toString(): String {
        |      return "west side";
        |    }
        |  }
        |}
        |""".trimMargin())
  }

  /** https://github.com/square/javapoet/issues/253  */
  @Test fun enumWithAnnotatedValues() {
    val roshambo = TypeSpec.enumBuilder("Roshambo")
        .addModifiers(Modifier.PUBLIC)
        .addEnumConstant("ROCK", TypeSpec.anonymousClassBuilder("")
            .addAnnotation(java.lang.Deprecated::class)
            .build())
        .addEnumConstant("PAPER")
        .addEnumConstant("SCISSORS")
        .build()
    assertThat(toString(roshambo)).isEqualTo("""
        |package com.squareup.tacos
        |
        |import java.lang.Deprecated
        |
        |public enum Roshambo {
        |  @Deprecated
        |  ROCK,
        |
        |  PAPER,
        |
        |  SCISSORS
        |}
        |""".trimMargin())
  }

  @Test fun funThrows() {
    val taco = TypeSpec.classBuilder("Taco")
        .addModifiers(Modifier.ABSTRACT)
        .addFun(FunSpec.builder("throwOne")
            .addException(IOException::class)
            .build())
        .addFun(FunSpec.builder("throwTwo")
            .addException(IOException::class)
            .addException(ClassName.get(tacosPackage, "SourCreamException"))
            .build())
        .addFun(FunSpec.builder("abstractThrow")
            .addModifiers(Modifier.ABSTRACT)
            .addException(IOException::class)
            .build())
        .addFun(FunSpec.builder("nativeThrow")
            .addModifiers(Modifier.NATIVE)
            .addException(IOException::class)
            .build())
        .build()
    assertThat(toString(taco)).isEqualTo("""
        |package com.squareup.tacos
        |
        |import java.io.IOException
        |
        |abstract class Taco {
        |  fun throwOne() throws IOException {
        |  }
        |
        |  fun throwTwo() throws IOException, SourCreamException {
        |  }
        |
        |  abstract fun abstractThrow() throws IOException;
        |
        |  native fun nativeThrow() throws IOException;
        |}
        |""".trimMargin())
  }

  @Test fun typeVariables() {
    val t = TypeVariableName.get("T")
    val p = TypeVariableName.get("P", Number::class)
    val location = ClassName.get(tacosPackage, "Location")
    val typeSpec = TypeSpec.classBuilder("Location")
        .addTypeVariable(t)
        .addTypeVariable(p)
        .addSuperinterface(ParameterizedTypeName.get(ClassName.get(Comparable::class), p))
        .addProperty(t, "label")
        .addProperty(p, "x")
        .addProperty(p, "y")
        .addFun(FunSpec.builder("compareTo")
            .addAnnotation(Override::class)
            .addModifiers(Modifier.PUBLIC)
            .returns(Int::class.javaPrimitiveType!!)
            .addParameter(p, "p")
            .addStatement("return 0")
            .build())
        .addFun(FunSpec.builder("of")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addTypeVariable(t)
            .addTypeVariable(p)
            .returns(ParameterizedTypeName.get(location, t, p))
            .addParameter(t, "label")
            .addParameter(p, "x")
            .addParameter(p, "y")
            .addStatement("throw new %T(%S)", UnsupportedOperationException::class, "TODO")
            .build())
        .build()
    assertThat(toString(typeSpec)).isEqualTo("""
        |package com.squareup.tacos
        |
        |import java.lang.Comparable
        |import java.lang.Number
        |import java.lang.Override
        |import java.lang.UnsupportedOperationException
        |import kotlin.Int
        |
        |class Location<T, P : Number> implements Comparable<P> {
        |  label: T;
        |
        |  x: P;
        |
        |  y: P;
        |
        |  @Override
        |  public fun compareTo(p: P): Int {
        |    return 0
        |  }
        |
        |  public static fun <T, P : Number> of(label: T, x: P, y: P): Location<T, P> {
        |    throw new UnsupportedOperationException("TODO")
        |  }
        |}
        |""".trimMargin())
  }

  @Test fun typeVariableWithBounds() {
    val a = AnnotationSpec.builder(ClassName.get("com.squareup.tacos", "A")).build()
    val p = TypeVariableName.get("P", Number::class)
    val q = TypeVariableName.get("Q", Number::class).annotated(a) as TypeVariableName
    val typeSpec = TypeSpec.classBuilder("Location")
        .addTypeVariable(p.withBounds(Comparable::class))
        .addTypeVariable(q.withBounds(Comparable::class))
        .addProperty(p, "x")
        .addProperty(q, "y")
        .build()
    assertThat(toString(typeSpec)).isEqualTo("""
        |package com.squareup.tacos
        |
        |import java.lang.Comparable
        |import java.lang.Number
        |
        |class Location<P : Number & Comparable, Q : Number & Comparable> {
        |  x: P;
        |
        |  y: @A Q;
        |}
        |""".trimMargin())
  }

  @Test fun classImplementsExtends() {
    val taco = ClassName.get(tacosPackage, "Taco")
    val food = ClassName.get("com.squareup.tacos", "Food")
    val typeSpec = TypeSpec.classBuilder("Taco")
        .addModifiers(Modifier.ABSTRACT)
        .superclass(ParameterizedTypeName.get(ClassName.get(AbstractSet::class), food))
        .addSuperinterface(Serializable::class)
        .addSuperinterface(ParameterizedTypeName.get(ClassName.get(Comparable::class), taco))
        .build()
    assertThat(toString(typeSpec)).isEqualTo("""
        |package com.squareup.tacos
        |
        |import java.io.Serializable
        |import java.lang.Comparable
        |import java.util.AbstractSet
        |
        |abstract class Taco extends AbstractSet<Food> implements Serializable, Comparable<Taco> {
        |}
        |""".trimMargin())
  }

  @Test fun classImplementsExtendsSameName() {
    val javapoetTaco = ClassName.get(tacosPackage, "Taco")
    val tacoBellTaco = ClassName.get("com.taco.bell", "Taco")
    val fishTaco = ClassName.get("org.fish.taco", "Taco")
    val typeSpec = TypeSpec.classBuilder("Taco")
        .superclass(fishTaco)
        .addSuperinterface(ParameterizedTypeName.get(ClassName.get(Comparable::class), javapoetTaco))
        .addSuperinterface(tacoBellTaco)
        .build()
    assertThat(toString(typeSpec)).isEqualTo("""
        |package com.squareup.tacos
        |
        |import java.lang.Comparable
        |
        |class Taco extends org.fish.taco.Taco implements Comparable<Taco>, com.taco.bell.Taco {
        |}
        |""".trimMargin())
  }

  @Test fun classImplementsNestedClass() {
    val outer = ClassName.get(tacosPackage, "Outer")
    val inner = outer.nestedClass("Inner")
    val callable = ClassName.get(Callable::class)
    val typeSpec = TypeSpec.classBuilder("Outer")
        .superclass(ParameterizedTypeName.get(callable,
            inner))
        .addType(TypeSpec.classBuilder("Inner")
            .addModifiers(Modifier.STATIC)
            .build())
        .build()

    assertThat(toString(typeSpec)).isEqualTo("""
        |package com.squareup.tacos
        |
        |import java.util.concurrent.Callable
        |
        |class Outer extends Callable<Outer.Inner> {
        |  static class Inner {
        |  }
        |}
        |""".trimMargin())
  }

  @Test fun enumImplements() {
    val typeSpec = TypeSpec.enumBuilder("Food")
        .addSuperinterface(Serializable::class)
        .addSuperinterface(Cloneable::class)
        .addEnumConstant("LEAN_GROUND_BEEF")
        .addEnumConstant("SHREDDED_CHEESE")
        .build()
    assertThat(toString(typeSpec)).isEqualTo("""
        |package com.squareup.tacos
        |
        |import java.io.Serializable
        |import java.lang.Cloneable
        |
        |enum Food implements Serializable, Cloneable {
        |  LEAN_GROUND_BEEF,
        |
        |  SHREDDED_CHEESE
        |}
        |""".trimMargin())
  }

  @Test fun interfaceExtends() {
    val taco = ClassName.get(tacosPackage, "Taco")
    val typeSpec = TypeSpec.interfaceBuilder("Taco")
        .addSuperinterface(Serializable::class)
        .addSuperinterface(ParameterizedTypeName.get(ClassName.get(Comparable::class), taco))
        .build()
    assertThat(toString(typeSpec)).isEqualTo("""
        |package com.squareup.tacos
        |
        |import java.io.Serializable
        |import java.lang.Comparable
        |
        |interface Taco extends Serializable, Comparable<Taco> {
        |}
        |""".trimMargin())
  }

  @Test fun nestedClasses() {
    val taco = ClassName.get(tacosPackage, "Combo", "Taco")
    val topping = ClassName.get(tacosPackage, "Combo", "Taco", "Topping")
    val chips = ClassName.get(tacosPackage, "Combo", "Chips")
    val sauce = ClassName.get(tacosPackage, "Combo", "Sauce")
    val typeSpec = TypeSpec.classBuilder("Combo")
        .addProperty(taco, "taco")
        .addProperty(chips, "chips")
        .addType(TypeSpec.classBuilder(taco.simpleName())
            .addModifiers(Modifier.STATIC)
            .addProperty(ParameterizedTypeName.get(ClassName.get(List::class), topping), "toppings")
            .addProperty(sauce, "sauce")
            .addType(TypeSpec.enumBuilder(topping.simpleName())
                .addEnumConstant("SHREDDED_CHEESE")
                .addEnumConstant("LEAN_GROUND_BEEF")
                .build())
            .build())
        .addType(TypeSpec.classBuilder(chips.simpleName())
            .addModifiers(Modifier.STATIC)
            .addProperty(topping, "topping")
            .addProperty(sauce, "dippingSauce")
            .build())
        .addType(TypeSpec.enumBuilder(sauce.simpleName())
            .addEnumConstant("SOUR_CREAM")
            .addEnumConstant("SALSA")
            .addEnumConstant("QUESO")
            .addEnumConstant("MILD")
            .addEnumConstant("FIRE")
            .build())
        .build()

    assertThat(toString(typeSpec)).isEqualTo("""
        |package com.squareup.tacos
        |
        |import java.util.List
        |
        |class Combo {
        |  taco: Taco;
        |
        |  chips: Chips;
        |
        |  static class Taco {
        |    toppings: List<Topping>;
        |
        |    sauce: Sauce;
        |
        |    enum Topping {
        |      SHREDDED_CHEESE,
        |
        |      LEAN_GROUND_BEEF
        |    }
        |  }
        |
        |  static class Chips {
        |    topping: Taco.Topping;
        |
        |    dippingSauce: Sauce;
        |  }
        |
        |  enum Sauce {
        |    SOUR_CREAM,
        |
        |    SALSA,
        |
        |    QUESO,
        |
        |    MILD,
        |
        |    FIRE
        |  }
        |}
        |""".trimMargin())
  }

  @Test fun annotation() {
    val annotation = TypeSpec.annotationBuilder("MyAnnotation")
        .addModifiers(Modifier.PUBLIC)
        .addFun(FunSpec.builder("test")
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .defaultValue("%L", 0)
            .returns(Int::class.javaPrimitiveType!!)
            .build())
        .build()

    assertThat(toString(annotation)).isEqualTo("""
        |package com.squareup.tacos
        |
        |import kotlin.Int
        |
        |public @interface MyAnnotation {
        |  fun test(): Int default 0;
        |}
        |""".trimMargin())
  }

  @Test fun innerAnnotationInAnnotationDeclaration() {
    val bar = TypeSpec.annotationBuilder("Bar")
        .addFun(FunSpec.builder("value")
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .defaultValue("@%T", java.lang.Deprecated::class)
            .returns(java.lang.Deprecated::class)
            .build())
        .build()

    assertThat(toString(bar)).isEqualTo("""
        |package com.squareup.tacos
        |
        |import java.lang.Deprecated
        |
        |@interface Bar {
        |  fun value(): Deprecated default @Deprecated;
        |}
        |""".trimMargin())
  }

  @Test fun annotationWithProperties() {
    val property = PropertySpec.builder(Int::class.javaPrimitiveType!!, "FOO")
        .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
        .initializer("%L", 101)
        .build()

    val anno = TypeSpec.annotationBuilder("Anno")
        .addProperty(property)
        .build()

    assertThat(toString(anno)).isEqualTo("""
        |package com.squareup.tacos
        |
        |import kotlin.Int
        |
        |@interface Anno {
        |  FOO: Int = 101;
        |}
        |""".trimMargin())
  }

  @Test fun classCannotHaveDefaultValueForFunction() {
    try {
      TypeSpec.classBuilder("Tacos")
          .addFun(FunSpec.builder("test")
              .addModifiers(Modifier.PUBLIC)
              .defaultValue("0")
              .returns(Int::class.javaPrimitiveType!!)
              .build())
          .build()
      fail()
    } catch (expected: IllegalStateException) {
    }

  }

  @Test fun classCannotHaveDefaultFunctions() {
    assumeTrue(isJava8)
    try {
      TypeSpec.classBuilder("Tacos")
          .addFun(FunSpec.builder("test")
              .addModifiers(Modifier.PUBLIC, Modifier.valueOf("DEFAULT"))
              .returns(Int::class.javaPrimitiveType!!)
              .addCode(CodeBlock.builder().addStatement("return 0").build())
              .build())
          .build()
      fail()
    } catch (expected: IllegalStateException) {
    }

  }

  @Test fun interfaceStaticFunctions() {
    val bar = TypeSpec.interfaceBuilder("Tacos")
        .addFun(FunSpec.builder("test")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(Int::class.javaPrimitiveType!!)
            .addCode(CodeBlock.builder().addStatement("return 0").build())
            .build())
        .build()

    assertThat(toString(bar)).isEqualTo("""
        |package com.squareup.tacos
        |
        |import kotlin.Int
        |
        |interface Tacos {
        |  static fun test(): Int {
        |    return 0
        |  }
        |}
        |""".trimMargin())
  }

  @Test fun interfaceDefaultFunctions() {
    assumeTrue(isJava8)
    val bar = TypeSpec.interfaceBuilder("Tacos")
        .addFun(FunSpec.builder("test")
            .addModifiers(Modifier.PUBLIC, Modifier.valueOf("DEFAULT"))
            .returns(Int::class.javaPrimitiveType!!)
            .addCode(CodeBlock.builder().addStatement("return 0").build())
            .build())
        .build()

    assertThat(toString(bar)).isEqualTo("""
        |package com.squareup.tacos
        |
        |import kotlin.Int
        |
        |interface Tacos {
        |  default fun test(): Int {
        |    return 0
        |  }
        |}
        |""".trimMargin())
  }

  @Test fun referencedAndDeclaredSimpleNamesConflict() {
    val internalTop = PropertySpec.builder(
        ClassName.get(tacosPackage, "Top"), "internalTop").build()
    val internalBottom = PropertySpec.builder(
        ClassName.get(tacosPackage, "Top", "Middle", "Bottom"), "internalBottom").build()
    val externalTop = PropertySpec.builder(
        ClassName.get(donutsPackage, "Top"), "externalTop").build()
    val externalBottom = PropertySpec.builder(
        ClassName.get(donutsPackage, "Bottom"), "externalBottom").build()
    val top = TypeSpec.classBuilder("Top")
        .addProperty(internalTop)
        .addProperty(internalBottom)
        .addProperty(externalTop)
        .addProperty(externalBottom)
        .addType(TypeSpec.classBuilder("Middle")
            .addProperty(internalTop)
            .addProperty(internalBottom)
            .addProperty(externalTop)
            .addProperty(externalBottom)
            .addType(TypeSpec.classBuilder("Bottom")
                .addProperty(internalTop)
                .addProperty(internalBottom)
                .addProperty(externalTop)
                .addProperty(externalBottom)
                .build())
            .build())
        .build()
    assertThat(toString(top)).isEqualTo("""
        |package com.squareup.tacos
        |
        |import com.squareup.donuts.Bottom
        |
        |class Top {
        |  internalTop: Top;
        |
        |  internalBottom: Middle.Bottom;
        |
        |  externalTop: com.squareup.donuts.Top;
        |
        |  externalBottom: Bottom;
        |
        |  class Middle {
        |    internalTop: Top;
        |
        |    internalBottom: Bottom;
        |
        |    externalTop: com.squareup.donuts.Top;
        |
        |    externalBottom: com.squareup.donuts.Bottom;
        |
        |    class Bottom {
        |      internalTop: Top;
        |
        |      internalBottom: Bottom;
        |
        |      externalTop: com.squareup.donuts.Top;
        |
        |      externalBottom: com.squareup.donuts.Bottom;
        |    }
        |  }
        |}
        |""".trimMargin())
  }

  @Test fun simpleNamesConflictInThisAndOtherPackage() {
    val internalOther = PropertySpec.builder(
        ClassName.get(tacosPackage, "Other"), "internalOther").build()
    val externalOther = PropertySpec.builder(
        ClassName.get(donutsPackage, "Other"), "externalOther").build()
    val gen = TypeSpec.classBuilder("Gen")
        .addProperty(internalOther)
        .addProperty(externalOther)
        .build()
    assertThat(toString(gen)).isEqualTo("""
        |package com.squareup.tacos
        |
        |class Gen {
        |  internalOther: Other;
        |
        |  externalOther: com.squareup.donuts.Other;
        |}
        |""".trimMargin())
  }

  @Test fun originatingElementsIncludesThoseOfNestedTypes() {
    val outerElement = Mockito.mock(Element::class.java)
    val innerElement = Mockito.mock(Element::class.java)
    val outer = TypeSpec.classBuilder("Outer")
        .addOriginatingElement(outerElement)
        .addType(TypeSpec.classBuilder("Inner")
            .addOriginatingElement(innerElement)
            .build())
        .build()
    assertThat(outer.originatingElements).containsExactly(outerElement, innerElement)
  }

  @Test fun intersectionType() {
    val typeVariable = TypeVariableName.get("T", Comparator::class, Serializable::class)
    val taco = TypeSpec.classBuilder("Taco")
        .addFun(FunSpec.builder("getComparator")
            .addTypeVariable(typeVariable)
            .returns(typeVariable)
            .addCode("return null;\n")
            .build())
        .build()
    assertThat(toString(taco)).isEqualTo("""
        |package com.squareup.tacos
        |
        |import java.io.Serializable
        |import java.util.Comparator
        |
        |class Taco {
        |  fun <T : Comparator & Serializable> getComparator(): T {
        |    return null;
        |  }
        |}
        |""".trimMargin())
  }

  @Test fun arrayType() {
    val taco = TypeSpec.classBuilder("Taco")
        .addProperty(IntArray::class, "ints")
        .build()
    assertThat(toString(taco)).isEqualTo("""
        |package com.squareup.tacos
        |
        |import kotlin.Array
        |import kotlin.Int
        |
        |class Taco {
        |  ints: Array<Int>;
        |}
        |""".trimMargin())
  }

  @Test fun kdoc() {
    val taco = TypeSpec.classBuilder("Taco")
        .addKdoc("A hard or soft tortilla, loosely folded and filled with whatever\n")
        .addKdoc("[random][%T] tex-mex stuff we could find in the pantry\n", Random::class)
        .addKdoc(CodeBlock.of("and some [%T] cheese.\n", String::class))
        .addProperty(PropertySpec.builder(Boolean::class.javaPrimitiveType!!, "soft")
            .addKdoc("True for a soft flour tortilla; false for a crunchy corn tortilla.\n")
            .build())
        .addFun(FunSpec.builder("refold")
            .addKdoc("Folds the back of this taco to reduce sauce leakage.\n"
                + "\n"
                + "For [%T#KOREAN], the front may also be folded.\n", Locale::class)
            .addParameter(Locale::class, "locale")
            .build())
        .build()
    // Mentioning a type in KDoc will not cause an import to be added (java.util.Random here), but
    // the short name will be used if it's already imported (java.util.Locale here).
    assertThat(toString(taco)).isEqualTo("""
        |package com.squareup.tacos
        |
        |import java.util.Locale
        |import kotlin.Boolean
        |
        |/**
        | * A hard or soft tortilla, loosely folded and filled with whatever
        | * [random][java.util.Random] tex-mex stuff we could find in the pantry
        | * and some [java.lang.String] cheese.
        | */
        |class Taco {
        |  /**
        |   * True for a soft flour tortilla; false for a crunchy corn tortilla.
        |   */
        |  soft: Boolean;
        |
        |  /**
        |   * Folds the back of this taco to reduce sauce leakage.
        |   *
        |   * For [Locale#KOREAN], the front may also be folded.
        |   */
        |  fun refold(locale: Locale) {
        |  }
        |}
        |""".trimMargin())
  }

  @Test fun annotationsInAnnotations() {
    val beef = ClassName.get(tacosPackage, "Beef")
    val chicken = ClassName.get(tacosPackage, "Chicken")
    val option = ClassName.get(tacosPackage, "Option")
    val mealDeal = ClassName.get(tacosPackage, "MealDeal")
    val menu = TypeSpec.classBuilder("Menu")
        .addAnnotation(AnnotationSpec.builder(mealDeal)
            .addMember("price", "%L", 500)
            .addMember("options", "%L", AnnotationSpec.builder(option)
                .addMember("name", "%S", "taco")
                .addMember("meat", "%T.class", beef)
                .build())
            .addMember("options", "%L", AnnotationSpec.builder(option)
                .addMember("name", "%S", "quesadilla")
                .addMember("meat", "%T.class", chicken)
                .build())
            .build())
        .build()
    assertThat(toString(menu)).isEqualTo("""
        |package com.squareup.tacos
        |
        |@MealDeal(
        |    price = 500,
        |    options = {
        |        @Option(name = "taco", meat = Beef.class),
        |        @Option(name = "quesadilla", meat = Chicken.class)
        |    }
        |)
        |class Menu {
        |}
        |""".trimMargin())
  }

  @Test fun varargs() {
    val taqueria = TypeSpec.classBuilder("Taqueria")
        .addFun(FunSpec.builder("prepare")
            .addParameter(Int::class.javaPrimitiveType!!, "workers")
            .addParameter(Array<Runnable>::class, "jobs")
            .varargs()
            .build())
        .build()
    assertThat(toString(taqueria)).isEqualTo("""
        |package com.squareup.tacos
        |
        |import java.lang.Runnable
        |import kotlin.Int
        |
        |class Taqueria {
        |  fun prepare(workers: Int, vararg jobs: Runnable) {
        |  }
        |}
        |""".trimMargin())
  }

  @Test fun codeBlocks() {
    val ifBlock = CodeBlock.builder()
        .beginControlFlow("if (!a.equals(b))")
        .addStatement("return i")
        .endControlFlow()
        .build()
    val funBody = CodeBlock.builder()
        .addStatement("%T size = %T.min(listA.size(), listB.size())", Int::class.javaPrimitiveType, Math::class)
        .beginControlFlow("for (%T i = 0; i < size; i++)", Int::class.javaPrimitiveType)
        .addStatement("%T %N = %N.get(i)", String::class, "a", "listA")
        .addStatement("%T %N = %N.get(i)", String::class, "b", "listB")
        .add("%L", ifBlock)
        .endControlFlow()
        .addStatement("return size")
        .build()
    val propertyBlock = CodeBlock.builder()
        .add("%>%>")
        .add("%T.<%T, %T>builder()%>%>", ImmutableMap::class, String::class, String::class)
        .add("\n.add(%S, %S)", '\'', "&#39;")
        .add("\n.add(%S, %S)", '&', "&amp;")
        .add("\n.add(%S, %S)", '<', "&lt;")
        .add("\n.add(%S, %S)", '>', "&gt;")
        .add("\n.build()%<%<")
        .add("%<%<")
        .build()
    val escapeHtml = PropertySpec.builder(ParameterizedTypeName.get(
        Map::class, String::class, String::class), "ESCAPE_HTML")
        .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
        .initializer(propertyBlock)
        .build()
    val util = TypeSpec.classBuilder("Util")
        .addProperty(escapeHtml)
        .addFun(FunSpec.builder("commonPrefixLength")
            .returns(Int::class.javaPrimitiveType!!)
            .addParameter(ParameterizedTypeName.get(List::class, String::class), "listA")
            .addParameter(ParameterizedTypeName.get(List::class, String::class), "listB")
            .addCode(funBody)
            .build())
        .build()
    assertThat(toString(util)).isEqualTo("""
        |package com.squareup.tacos
        |
        |import com.google.common.collect.ImmutableMap
        |import java.lang.Math
        |import java.lang.String
        |import java.util.List
        |import java.util.Map
        |import kotlin.Int
        |
        |class Util {
        |  private static final ESCAPE_HTML: Map<String, String> = ImmutableMap.<String, String>builder()
        |          .add("'", "&#39;")
        |          .add("&", "&amp;")
        |          .add("<", "&lt;")
        |          .add(">", "&gt;")
        |          .build();
        |
        |  fun commonPrefixLength(listA: List<String>, listB: List<String>): Int {
        |    Int size = Math.min(listA.size(), listB.size())
        |    for (Int i = 0; i < size; i++) {
        |      String a = listA.get(i)
        |      String b = listB.get(i)
        |      if (!a.equals(b)) {
        |        return i
        |      }
        |    }
        |    return size
        |  }
        |}
        |""".trimMargin())
  }

  @Test fun indexedElseIf() {
    val taco = TypeSpec.classBuilder("Taco")
        .addFun(FunSpec.builder("choices")
            .beginControlFlow("if (%1L != null || %1L == %2L)", "taco", "otherTaco")
            .addStatement("%T.out.println(%S)", System::class, "only one taco? NOO!")
            .nextControlFlow("else if (%1L.%3L && %2L.%3L)", "taco", "otherTaco", "isSupreme()")
            .addStatement("%T.out.println(%S)", System::class, "taco heaven")
            .endControlFlow()
            .build())
        .build()
    assertThat(toString(taco)).isEqualTo("""
        |package com.squareup.tacos
        |
        |import java.lang.System
        |
        |class Taco {
        |  fun choices() {
        |    if (taco != null || taco == otherTaco) {
        |      System.out.println("only one taco? NOO!")
        |    } else if (taco.isSupreme() && otherTaco.isSupreme()) {
        |      System.out.println("taco heaven")
        |    }
        |  }
        |}
        |""".trimMargin())
  }

  @Test fun elseIf() {
    val taco = TypeSpec.classBuilder("Taco")
        .addFun(FunSpec.builder("choices")
            .beginControlFlow("if (5 < 4) ")
            .addStatement("%T.out.println(%S)", System::class, "wat")
            .nextControlFlow("else if (5 < 6)")
            .addStatement("%T.out.println(%S)", System::class, "hello")
            .endControlFlow()
            .build())
        .build()
    assertThat(toString(taco)).isEqualTo("""
        |package com.squareup.tacos
        |
        |import java.lang.System
        |
        |class Taco {
        |  fun choices() {
        |    if (5 < 4)  {
        |      System.out.println("wat")
        |    } else if (5 < 6) {
        |      System.out.println("hello")
        |    }
        |  }
        |}
        |""".trimMargin())
  }

  @Test fun inlineIndent() {
    val taco = TypeSpec.classBuilder("Taco")
        .addFun(FunSpec.builder("inlineIndent")
            .addCode("if (3 < 4) {\n%>%T.out.println(%S);\n%<}\n", System::class, "hello")
            .build())
        .build()
    assertThat(toString(taco)).isEqualTo("""
        |package com.squareup.tacos
        |
        |import java.lang.System
        |
        |class Taco {
        |  fun inlineIndent() {
        |    if (3 < 4) {
        |      System.out.println("hello");
        |    }
        |  }
        |}
        |""".trimMargin())
  }

  @Test fun defaultModifiersForInterfaceMembers() {
    val taco = TypeSpec.interfaceBuilder("Taco")
        .addProperty(PropertySpec.builder(String::class, "SHELL")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .initializer("%S", "crunchy corn")
            .build())
        .addFun(FunSpec.builder("fold")
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .build())
        .addType(TypeSpec.classBuilder("Topping")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .build())
        .build()
    assertThat(toString(taco)).isEqualTo("""
        |package com.squareup.tacos
        |
        |import java.lang.String
        |
        |interface Taco {
        |  SHELL: String = "crunchy corn";
        |
        |  fun fold();
        |
        |  class Topping {
        |  }
        |}
        |""".trimMargin())
  }

  @Test fun defaultModifiersForMemberInterfacesAndEnums() {
    val taco = TypeSpec.classBuilder("Taco")
        .addType(TypeSpec.classBuilder("Meat")
            .addModifiers(Modifier.STATIC)
            .build())
        .addType(TypeSpec.interfaceBuilder("Tortilla")
            .addModifiers(Modifier.STATIC)
            .build())
        .addType(TypeSpec.enumBuilder("Topping")
            .addModifiers(Modifier.STATIC)
            .addEnumConstant("SALSA")
            .build())
        .build()
    assertThat(toString(taco)).isEqualTo("""
        |package com.squareup.tacos
        |
        |class Taco {
        |  static class Meat {
        |  }
        |
        |  interface Tortilla {
        |  }
        |
        |  enum Topping {
        |    SALSA
        |  }
        |}
        |""".trimMargin())
  }

  @Test fun membersOrdering() {
    // Hand out names in reverse-alphabetical order to defend against unexpected sorting.
    val taco = TypeSpec.classBuilder("Members")
        .addType(TypeSpec.classBuilder("Z").build())
        .addType(TypeSpec.classBuilder("Y").build())
        .addProperty(String::class, "X", Modifier.STATIC)
        .addProperty(String::class, "W")
        .addProperty(String::class, "V", Modifier.STATIC)
        .addProperty(String::class, "U")
        .addFun(FunSpec.builder("T").addModifiers(Modifier.STATIC).build())
        .addFun(FunSpec.builder("S").build())
        .addFun(FunSpec.builder("R").addModifiers(Modifier.STATIC).build())
        .addFun(FunSpec.builder("Q").build())
        .addFun(FunSpec.constructorBuilder()
            .addParameter(Int::class.javaPrimitiveType!!, "p")
            .build())
        .addFun(FunSpec.constructorBuilder()
            .addParameter(Long::class.javaPrimitiveType!!, "o")
            .build())
        .build()
    // Static properties, instance properties, constructors, functions, classes.
    assertThat(toString(taco)).isEqualTo("""
        |package com.squareup.tacos
        |
        |import java.lang.String
        |import kotlin.Int
        |import kotlin.Long
        |
        |class Members {
        |  static X: String;
        |
        |  static V: String;
        |
        |  W: String;
        |
        |  U: String;
        |
        |  constructor(p: Int) {
        |  }
        |
        |  constructor(o: Long) {
        |  }
        |
        |  static fun T() {
        |  }
        |
        |  fun S() {
        |  }
        |
        |  static fun R() {
        |  }
        |
        |  fun Q() {
        |  }
        |
        |  class Z {
        |  }
        |
        |  class Y {
        |  }
        |}
        |""".trimMargin())
  }

  @Test fun nativeFunctions() {
    val taco = TypeSpec.classBuilder("Taco")
        .addFun(FunSpec.builder("nativeInt")
            .addModifiers(Modifier.NATIVE)
            .returns(Int::class.javaPrimitiveType!!)
            .build())
        // GWT JSNI
        .addFun(FunSpec.builder("alert")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.NATIVE)
            .addParameter(String::class, "msg")
            .addCode(CodeBlock.builder()
                .add(" /*-{\n")
                .indent()
                .addStatement("\$wnd.alert(msg);")
                .unindent()
                .add("}-*/")
                .build())
            .build())
        .build()
    assertThat(toString(taco)).isEqualTo("""
        |package com.squareup.tacos
        |
        |import java.lang.String
        |import kotlin.Int
        |
        |class Taco {
        |  native fun nativeInt(): Int;
        |
        |  public static native fun alert(msg: String) /*-{
        |    ${"$"}wnd.alert(msg);
        |  }-*/;
        |}
        |""".trimMargin())
  }

  @Test fun nullStringLiteral() {
    val taco = TypeSpec.classBuilder("Taco")
        .addProperty(PropertySpec.builder(String::class, "NULL")
            .initializer("%S", null)
            .build())
        .build()
    assertThat(toString(taco)).isEqualTo("""
        |package com.squareup.tacos
        |
        |import java.lang.String
        |
        |class Taco {
        |  NULL: String = null;
        |}
        |""".trimMargin())
  }

  @Test fun annotationToString() {
    val annotation = AnnotationSpec.builder(SuppressWarnings::class)
        .addMember("value", "%S", "unused")
        .build()
    assertThat(annotation.toString()).isEqualTo("@java.lang.SuppressWarnings(\"unused\")")
  }

  @Test fun codeBlockToString() {
    val codeBlock = CodeBlock.builder()
        .addStatement("%T %N = %S.substring(0, 3)", String::class, "s", "taco")
        .build()
    assertThat(codeBlock.toString()).isEqualTo("java.lang.String s = \"taco\".substring(0, 3)\n")
  }

  @Test fun propertyToString() {
    val property = PropertySpec.builder(String::class, "s", Modifier.FINAL)
        .initializer("%S.substring(0, 3)", "taco")
        .build()
    assertThat(property.toString())
        .isEqualTo("final s: java.lang.String = \"taco\".substring(0, 3);\n")
  }

  @Test fun functionToString() {
    val funSpec = FunSpec.builder("toString")
        .addAnnotation(Override::class)
        .addModifiers(Modifier.PUBLIC)
        .returns(String::class)
        .addStatement("return %S", "taco")
        .build()
    assertThat(funSpec.toString()).isEqualTo(""
        + "@java.lang.Override\n"
        + "public fun toString(): java.lang.String {\n"
        + "  return \"taco\"\n"
        + "}\n")
  }

  @Test fun constructorToString() {
    val constructor = FunSpec.constructorBuilder()
        .addModifiers(Modifier.PUBLIC)
        .addParameter(ClassName.get(tacosPackage, "Taco"), "taco")
        .addStatement("this.%N = %N", "taco", "taco")
        .build()
    assertThat(constructor.toString()).isEqualTo(""
        + "public constructor(taco: com.squareup.tacos.Taco) {\n"
        + "  this.taco = taco\n"
        + "}\n")
  }

  @Test fun parameterToString() {
    val parameter = ParameterSpec.builder(ClassName.get(tacosPackage, "Taco"), "taco")
        .addModifiers(Modifier.FINAL)
        .addAnnotation(ClassName.get("javax.annotation", "Nullable"))
        .build()
    assertThat(parameter.toString())
        .isEqualTo("@javax.annotation.Nullable final taco: com.squareup.tacos.Taco")
  }

  @Test fun classToString() {
    val type = TypeSpec.classBuilder("Taco")
        .build()
    assertThat(type.toString()).isEqualTo(""
        + "class Taco {\n"
        + "}\n")
  }

  @Test fun anonymousClassToString() {
    val type = TypeSpec.anonymousClassBuilder("")
        .addSuperinterface(Runnable::class)
        .addFun(FunSpec.builder("run")
            .addAnnotation(Override::class)
            .addModifiers(Modifier.PUBLIC)
            .build())
        .build()
    assertThat(type.toString()).isEqualTo("""
        |new java.lang.Runnable() {
        |  @java.lang.Override
        |  public fun run() {
        |  }
        |}""".trimMargin())
  }

  @Test fun interfaceClassToString() {
    val type = TypeSpec.interfaceBuilder("Taco")
        .build()
    assertThat(type.toString()).isEqualTo("""
        |interface Taco {
        |}
        |""".trimMargin())
  }

  @Test fun annotationDeclarationToString() {
    val type = TypeSpec.annotationBuilder("Taco")
        .build()
    assertThat(type.toString()).isEqualTo("""
        |@interface Taco {
        |}
        |""".trimMargin())
  }

  private fun toString(typeSpec: TypeSpec): String {
    return KotlinFile.get(tacosPackage, typeSpec).toString()
  }

  @Test fun multilineStatement() {
    val taco = TypeSpec.classBuilder("Taco")
        .addFun(FunSpec.builder("toString")
            .addAnnotation(Override::class)
            .addModifiers(Modifier.PUBLIC)
            .returns(String::class)
            .addStatement("return %S\n+ %S\n+ %S\n+ %S\n+ %S",
                "Taco(", "beef,", "lettuce,", "cheese", ")")
            .build())
        .build()
    assertThat(toString(taco)).isEqualTo("""
        |package com.squareup.tacos
        |
        |import java.lang.Override
        |import java.lang.String
        |
        |class Taco {
        |  @Override
        |  public fun toString(): String {
        |    return "Taco("
        |        + "beef,"
        |        + "lettuce,"
        |        + "cheese"
        |        + ")"
        |  }
        |}
        |""".trimMargin())
  }

  @Test fun multilineStatementWithAnonymousClass() {
    val stringComparator = ParameterizedTypeName.get(Comparator::class, String::class)
    val listOfString = ParameterizedTypeName.get(List::class, String::class)
    val prefixComparator = TypeSpec.anonymousClassBuilder("")
        .addSuperinterface(stringComparator)
        .addFun(FunSpec.builder("compare")
            .addAnnotation(Override::class)
            .addModifiers(Modifier.PUBLIC)
            .returns(Int::class.javaPrimitiveType!!)
            .addParameter(String::class, "a")
            .addParameter(String::class, "b")
            .addStatement("return a.substring(0, length)\n" + ".compareTo(b.substring(0, length))")
            .build())
        .build()
    val taco = TypeSpec.classBuilder("Taco")
        .addFun(FunSpec.builder("comparePrefix")
            .returns(stringComparator)
            .addParameter(Int::class.javaPrimitiveType!!, "length", Modifier.FINAL)
            .addStatement("return %L", prefixComparator)
            .build())
        .addFun(FunSpec.builder("sortPrefix")
            .addParameter(listOfString, "list")
            .addParameter(Int::class.javaPrimitiveType!!, "length", Modifier.FINAL)
            .addStatement("%T.sort(\nlist,\n%L)", Collections::class, prefixComparator)
            .build())
        .build()
    assertThat(toString(taco)).isEqualTo("""
        |package com.squareup.tacos
        |
        |import java.lang.Override
        |import java.lang.String
        |import java.util.Collections
        |import java.util.Comparator
        |import java.util.List
        |import kotlin.Int
        |
        |class Taco {
        |  fun comparePrefix(final length: Int): Comparator<String> {
        |    return new Comparator<String>() {
        |      @Override
        |      public fun compare(a: String, b: String): Int {
        |        return a.substring(0, length)
        |            .compareTo(b.substring(0, length))
        |      }
        |    }
        |  }
        |
        |  fun sortPrefix(list: List<String>, final length: Int) {
        |    Collections.sort(
        |        list,
        |        new Comparator<String>() {
        |          @Override
        |          public fun compare(a: String, b: String): Int {
        |            return a.substring(0, length)
        |                .compareTo(b.substring(0, length))
        |          }
        |        })
        |  }
        |}
        |""".trimMargin())
  }

  @Test fun multilineStrings() {
    val taco = TypeSpec.classBuilder("Taco")
        .addProperty(PropertySpec.builder(String::class, "toppings")
            .initializer("%S", "shell\nbeef\nlettuce\ncheese\n")
            .build())
        .build()
    assertThat(toString(taco)).isEqualTo("""
        |package com.squareup.tacos
        |
        |import java.lang.String
        |
        |class Taco {
        |  toppings: String = "shell\n"
        |      + "beef\n"
        |      + "lettuce\n"
        |      + "cheese\n";
        |}
        |""".trimMargin())
  }

  @Test fun doublePropertyInitialization() {
    try {
      PropertySpec.builder(String::class, "listA")
          .initializer("foo")
          .initializer("bar")
          .build()
      fail()
    } catch (expected: IllegalStateException) {
    }

    try {
      PropertySpec.builder(String::class, "listA")
          .initializer(CodeBlock.builder().add("foo").build())
          .initializer(CodeBlock.builder().add("bar").build())
          .build()
      fail()
    } catch (expected: IllegalStateException) {
    }

  }

  @Test fun multipleAnnotationAddition() {
    val taco = TypeSpec.classBuilder("Taco")
        .addAnnotations(Arrays.asList(
            AnnotationSpec.builder(SuppressWarnings::class)
                .addMember("value", "%S", "unchecked")
                .build(),
            AnnotationSpec.builder(java.lang.Deprecated::class).build()))
        .build()
    assertThat(toString(taco)).isEqualTo("""
        |package com.squareup.tacos
        |
        |import java.lang.Deprecated
        |import java.lang.SuppressWarnings
        |
        |@SuppressWarnings("unchecked")
        |@Deprecated
        |class Taco {
        |}
        |""".trimMargin())
  }

  @Test fun multiplePropertyAddition() {
    val taco = TypeSpec.classBuilder("Taco")
        .addProperties(Arrays.asList(
            PropertySpec.builder(Int::class.javaPrimitiveType!!,
                "ANSWER", Modifier.STATIC, Modifier.FINAL).build(),
            PropertySpec.builder(BigDecimal::class, "price", Modifier.PRIVATE).build()))
        .build()
    assertThat(toString(taco)).isEqualTo("""
        |package com.squareup.tacos
        |
        |import java.math.BigDecimal
        |import kotlin.Int
        |
        |class Taco {
        |  static final ANSWER: Int;
        |
        |  private price: BigDecimal;
        |}
        |""".trimMargin())
  }

  @Test fun multipleFunctionAddition() {
    val taco = TypeSpec.classBuilder("Taco")
        .addFunctions(Arrays.asList(
            FunSpec.builder("getAnswer")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(Int::class.javaPrimitiveType!!)
                .addStatement("return %L", 42)
                .build(),
            FunSpec.builder("getRandomQuantity")
                .addModifiers(Modifier.PUBLIC)
                .returns(Int::class.javaPrimitiveType!!)
                .addKdoc("chosen by fair dice roll ;)")
                .addStatement("return %L", 4)
                .build()))
        .build()
    assertThat(toString(taco)).isEqualTo("""
        |package com.squareup.tacos
        |
        |import kotlin.Int
        |
        |class Taco {
        |  public static fun getAnswer(): Int {
        |    return 42
        |  }
        |
        |  /**
        |   * chosen by fair dice roll ;) */
        |  public fun getRandomQuantity(): Int {
        |    return 4
        |  }
        |}
        |""".trimMargin())
  }

  @Test fun multipleSuperinterfaceAddition() {
    val taco = TypeSpec.classBuilder("Taco")
        .addSuperinterfaces(Arrays.asList(
            TypeName.get(Serializable::class),
            TypeName.get(EventListener::class)))
        .build()
    assertThat(toString(taco)).isEqualTo("""
        |package com.squareup.tacos
        |
        |import java.io.Serializable
        |import java.util.EventListener
        |
        |class Taco implements Serializable, EventListener {
        |}
        |""".trimMargin())
  }

  @Test fun multipleTypeVariableAddition() {
    val location = TypeSpec.classBuilder("Location")
        .addTypeVariables(Arrays.asList(
            TypeVariableName.get("T"),
            TypeVariableName.get("P", Number::class)))
        .build()
    assertThat(toString(location)).isEqualTo("""
        |package com.squareup.tacos
        |
        |import java.lang.Number
        |
        |class Location<T, P : Number> {
        |}
        |""".trimMargin())
  }

  @Test fun multipleTypeAddition() {
    val taco = TypeSpec.classBuilder("Taco")
        .addTypes(Arrays.asList(
            TypeSpec.classBuilder("Topping").build(),
            TypeSpec.classBuilder("Sauce").build()))
        .build()
    assertThat(toString(taco)).isEqualTo("""
        |package com.squareup.tacos
        |
        |class Taco {
        |  class Topping {
        |  }
        |
        |  class Sauce {
        |  }
        |}
        |""".trimMargin())
  }

  @Test fun tryCatch() {
    val taco = TypeSpec.classBuilder("Taco")
        .addFun(FunSpec.builder("addTopping")
            .addParameter(ClassName.get("com.squareup.tacos", "Topping"), "topping")
            .beginControlFlow("try")
            .addCode("/* do something tricky with the topping */\n")
            .nextControlFlow("catch (e: %T)",
                ClassName.get("com.squareup.tacos", "IllegalToppingException"))
            .endControlFlow()
            .build())
        .build()
    assertThat(toString(taco)).isEqualTo("""
        |package com.squareup.tacos
        |
        |class Taco {
        |  fun addTopping(topping: Topping) {
        |    try {
        |      /* do something tricky with the topping */
        |    } catch (e: IllegalToppingException) {
        |    }
        |  }
        |}
        |""".trimMargin())
  }

  @Test fun ifElse() {
    val taco = TypeSpec.classBuilder("Taco")
        .addFun(FunSpec.builder("isDelicious")
            .addParameter(INT, "count")
            .returns(BOOLEAN)
            .beginControlFlow("if (count > 0)")
            .addStatement("return true")
            .nextControlFlow("else")
            .addStatement("return false")
            .endControlFlow()
            .build())
        .build()
    assertThat(toString(taco)).isEqualTo("""
        |package com.squareup.tacos
        |
        |import kotlin.Boolean
        |import kotlin.Int
        |
        |class Taco {
        |  fun isDelicious(count: Int): Boolean {
        |    if (count > 0) {
        |      return true
        |    } else {
        |      return false
        |    }
        |  }
        |}
        |""".trimMargin())
  }

  @Test fun literalFromAnything() {
    val value = object : Any() {
      override fun toString(): String {
        return "foo"
      }
    }
    assertThat(CodeBlock.of("%L", value).toString()).isEqualTo("foo")
  }

  @Test fun nameFromCharSequence() {
    assertThat(CodeBlock.of("%N", "text").toString()).isEqualTo("text")
  }

  @Test fun nameFromProperty() {
    val property = PropertySpec.builder(String::class, "property").build()
    assertThat(CodeBlock.of("%N", property).toString()).isEqualTo("property")
  }

  @Test fun nameFromParameter() {
    val parameter = ParameterSpec.builder(String::class, "parameter").build()
    assertThat(CodeBlock.of("%N", parameter).toString()).isEqualTo("parameter")
  }

  @Test fun nameFromFunction() {
    val funSpec = FunSpec.builder("method")
        .addModifiers(Modifier.ABSTRACT)
        .returns(String::class)
        .build()
    assertThat(CodeBlock.of("%N", funSpec).toString()).isEqualTo("method")
  }

  @Test fun nameFromType() {
    val type = TypeSpec.classBuilder("Type").build()
    assertThat(CodeBlock.of("%N", type).toString()).isEqualTo("Type")
  }

  @Test fun nameFromUnsupportedType() {
    try {
      CodeBlock.builder().add("%N", String::class)
      fail()
    } catch (expected: IllegalArgumentException) {
      assertThat(expected).hasMessage("expected name but was " + String::class)
    }

  }

  @Test fun stringFromAnything() {
    val value = object : Any() {
      override fun toString(): String {
        return "foo"
      }
    }
    assertThat(CodeBlock.of("%S", value).toString()).isEqualTo("\"foo\"")
  }

  @Test fun stringFromNull() {
    assertThat(CodeBlock.of("%S", null).toString()).isEqualTo("null")
  }

  @Test fun typeFromTypeName() {
    val typeName = TypeName.get(String::class)
    assertThat(CodeBlock.of("%T", typeName).toString()).isEqualTo("java.lang.String")
  }

  @Test fun typeFromTypeMirror() {
    val mirror = getElement(String::class).asType()
    assertThat(CodeBlock.of("%T", mirror).toString()).isEqualTo("java.lang.String")
  }

  @Test fun typeFromTypeElement() {
    val element = getElement(String::class)
    assertThat(CodeBlock.of("%T", element).toString()).isEqualTo("java.lang.String")
  }

  @Test fun typeFromReflectType() {
    assertThat(CodeBlock.of("%T", String::class).toString()).isEqualTo("java.lang.String")
  }

  @Test fun typeFromUnsupportedType() {
    try {
      CodeBlock.builder().add("%T", "java.lang.String")
      fail()
    } catch (expected: IllegalArgumentException) {
      assertThat(expected).hasMessage("expected type but was java.lang.String")
    }

  }

  @Test fun tooFewArguments() {
    try {
      CodeBlock.builder().add("%S")
      fail()
    } catch (expected: IllegalArgumentException) {
      assertThat(expected).hasMessage("index 1 for '%S' not in range (received 0 arguments)")
    }

  }

  @Test fun unusedArgumentsRelative() {
    try {
      CodeBlock.builder().add("%L %L", "a", "b", "c")
      fail()
    } catch (expected: IllegalArgumentException) {
      assertThat(expected).hasMessage("unused arguments: expected 2, received 3")
    }

  }

  @Test fun unusedArgumentsIndexed() {
    try {
      CodeBlock.builder().add("%1L %2L", "a", "b", "c")
      fail()
    } catch (expected: IllegalArgumentException) {
      assertThat(expected).hasMessage("unused argument: %3")
    }

    try {
      CodeBlock.builder().add("%1L %1L %1L", "a", "b", "c")
      fail()
    } catch (expected: IllegalArgumentException) {
      assertThat(expected).hasMessage("unused arguments: %2, %3")
    }

    try {
      CodeBlock.builder().add("%3L %1L %3L %1L %3L", "a", "b", "c", "d")
      fail()
    } catch (expected: IllegalArgumentException) {
      assertThat(expected).hasMessage("unused arguments: %2, %4")
    }

  }

  @Test fun superClassOnlyValidForClasses() {
    try {
      TypeSpec.annotationBuilder("A").superclass(ClassName.get(Any::class))
      fail()
    } catch (expected: IllegalStateException) {
    }

    try {
      TypeSpec.enumBuilder("E").superclass(ClassName.get(Any::class))
      fail()
    } catch (expected: IllegalStateException) {
    }

    try {
      TypeSpec.interfaceBuilder("I").superclass(ClassName.get(Any::class))
      fail()
    } catch (expected: IllegalStateException) {
    }

  }

  @Test fun invalidSuperClass() {
    try {
      TypeSpec.classBuilder("foo")
          .superclass(ClassName.get(List::class))
          .superclass(ClassName.get(Map::class))
      fail()
    } catch (expected: IllegalStateException) {
    }

  }

  @Test fun staticCodeBlock() {
    val taco = TypeSpec.classBuilder("Taco")
        .addProperty(String::class, "foo", Modifier.PRIVATE)
        .addProperty(String::class, "FOO", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
        .addStaticBlock(CodeBlock.builder()
            .addStatement("FOO = %S", "FOO")
            .build())
        .addFun(FunSpec.builder("toString")
            .addAnnotation(Override::class)
            .addModifiers(Modifier.PUBLIC)
            .returns(String::class)
            .addStatement("return FOO")
            .build())
        .build()
    assertThat(toString(taco)).isEqualTo("""
        |package com.squareup.tacos
        |
        |import java.lang.Override
        |import java.lang.String
        |
        |class Taco {
        |  private static final FOO: String;
        |
        |  static {
        |    FOO = "FOO"
        |  }
        |
        |  private foo: String;
        |
        |  @Override
        |  public fun toString(): String {
        |    return FOO
        |  }
        |}
        |""".trimMargin())
  }

  @Test fun initializerBlockInRightPlace() {
    val taco = TypeSpec.classBuilder("Taco")
        .addProperty(String::class, "foo", Modifier.PRIVATE)
        .addProperty(String::class, "FOO", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
        .addStaticBlock(CodeBlock.builder()
            .addStatement("FOO = %S", "FOO")
            .build())
        .addFun(FunSpec.constructorBuilder().build())
        .addFun(FunSpec.builder("toString")
            .addAnnotation(Override::class)
            .addModifiers(Modifier.PUBLIC)
            .returns(String::class)
            .addStatement("return FOO")
            .build())
        .addInitializerBlock(CodeBlock.builder()
            .addStatement("foo = %S", "FOO")
            .build())
        .build()
    assertThat(toString(taco)).isEqualTo("""
        |package com.squareup.tacos
        |
        |import java.lang.Override
        |import java.lang.String
        |
        |class Taco {
        |  private static final FOO: String;
        |
        |  static {
        |    FOO = "FOO"
        |  }
        |
        |  private foo: String;
        |
        |  {
        |    foo = "FOO"
        |  }
        |
        |  constructor() {
        |  }
        |
        |  @Override
        |  public fun toString(): String {
        |    return FOO
        |  }
        |}
        |""".trimMargin())
  }

  @Test fun initializersToBuilder() {
    // Tests if toBuilder() contains correct static and instance initializers
    val taco = TypeSpec.classBuilder("Taco")
        .addProperty(String::class, "foo", Modifier.PRIVATE)
        .addProperty(String::class, "FOO", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
        .addStaticBlock(CodeBlock.builder()
            .addStatement("FOO = %S", "FOO")
            .build())
        .addFun(FunSpec.constructorBuilder().build())
        .addFun(FunSpec.builder("toString")
            .addAnnotation(Override::class)
            .addModifiers(Modifier.PUBLIC)
            .returns(String::class)
            .addStatement("return FOO")
            .build())
        .addInitializerBlock(CodeBlock.builder()
            .addStatement("foo = %S", "FOO")
            .build())
        .build()

    val recreatedTaco = taco.toBuilder().build()
    assertThat(toString(taco)).isEqualTo(toString(recreatedTaco))

    val initializersAdded = taco.toBuilder()
        .addInitializerBlock(CodeBlock.builder()
            .addStatement("foo = %S", "instanceFoo")
            .build())
        .addStaticBlock(CodeBlock.builder()
            .addStatement("FOO = %S", "staticFoo")
            .build())
        .build()

    assertThat(toString(initializersAdded)).isEqualTo("""
        |package com.squareup.tacos
        |
        |import java.lang.Override
        |import java.lang.String
        |
        |class Taco {
        |  private static final FOO: String;
        |
        |  static {
        |    FOO = "FOO"
        |  }
        |  static {
        |    FOO = "staticFoo"
        |  }
        |
        |  private foo: String;
        |
        |  {
        |    foo = "FOO"
        |  }
        |  {
        |    foo = "instanceFoo"
        |  }
        |
        |  constructor() {
        |  }
        |
        |  @Override
        |  public fun toString(): String {
        |    return FOO
        |  }
        |}
        |""".trimMargin())
  }

  @Test fun initializerBlockUnsupportedExceptionOnInterface() {
    val interfaceBuilder = TypeSpec.interfaceBuilder("Taco")
    try {
      interfaceBuilder.addInitializerBlock(CodeBlock.builder().build())
      fail("Exception expected")
    } catch (expected: IllegalStateException) {
    }
  }

  @Test fun initializerBlockUnsupportedExceptionOnAnnotation() {
    val annotationBuilder = TypeSpec.annotationBuilder("Taco")
    try {
      annotationBuilder.addInitializerBlock(CodeBlock.builder().build())
      fail("Exception expected")
    } catch (expected: IllegalStateException) {
    }
  }

  @Test fun lineWrapping() {
    val funSpecBuilder = FunSpec.builder("call")
    funSpecBuilder.addCode("%[call(")
    for (i in 0..31) {
      funSpecBuilder.addParameter(String::class, "s" + i)
      funSpecBuilder.addCode(if (i > 0) ",%W%S" else "%S", i)
    }
    funSpecBuilder.addCode(");%]\n")

    val taco = TypeSpec.classBuilder("Taco")
        .addFun(funSpecBuilder.build())
        .build()
    assertThat(toString(taco)).isEqualTo("""
        |package com.squareup.tacos
        |
        |import java.lang.String
        |
        |class Taco {
        |  fun call(s0: String, s1: String, s2: String, s3: String, s4: String, s5: String, s6: String,
        |      s7: String, s8: String, s9: String, s10: String, s11: String, s12: String, s13: String,
        |      s14: String, s15: String, s16: String, s17: String, s18: String, s19: String, s20: String,
        |      s21: String, s22: String, s23: String, s24: String, s25: String, s26: String, s27: String,
        |      s28: String, s29: String, s30: String, s31: String) {
        |    call("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16",
        |        "17", "18", "19", "20", "21", "22", "23", "24", "25", "26", "27", "28", "29", "30", "31");
        |  }
        |}
        |""".trimMargin())
  }

  @Test fun equalsAndHashCode() {
    var a = TypeSpec.interfaceBuilder("taco").build()
    var b = TypeSpec.interfaceBuilder("taco").build()
    assertThat(a == b).isTrue()
    assertThat(a.hashCode()).isEqualTo(b.hashCode())
    a = TypeSpec.classBuilder("taco").build()
    b = TypeSpec.classBuilder("taco").build()
    assertThat(a == b).isTrue()
    assertThat(a.hashCode()).isEqualTo(b.hashCode())
    a = TypeSpec.enumBuilder("taco").addEnumConstant("SALSA").build()
    b = TypeSpec.enumBuilder("taco").addEnumConstant("SALSA").build()
    assertThat(a == b).isTrue()
    assertThat(a.hashCode()).isEqualTo(b.hashCode())
    a = TypeSpec.annotationBuilder("taco").build()
    b = TypeSpec.annotationBuilder("taco").build()
    assertThat(a == b).isTrue()
    assertThat(a.hashCode()).isEqualTo(b.hashCode())
  }

  @Test fun classNameFactories() {
    val className = ClassName.get("com.example", "Example")
    assertThat(TypeSpec.classBuilder(className).build().name).isEqualTo("Example")
    assertThat(TypeSpec.interfaceBuilder(className).build().name).isEqualTo("Example")
    assertThat(TypeSpec.enumBuilder(className).addEnumConstant("A").build().name).isEqualTo("Example")
    assertThat(TypeSpec.annotationBuilder(className).build().name).isEqualTo("Example")
  }

  companion object {
    private val donutsPackage = "com.squareup.donuts"
  }
}
