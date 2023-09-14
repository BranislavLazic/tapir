package sttp.tapir.json.pickler

import sttp.tapir.Codec.JsonCodec
import sttp.tapir.DecodeResult.Error.JsonDecodeException
import sttp.tapir.DecodeResult.{Error, Value}
import sttp.tapir.SchemaType.SProduct
import sttp.tapir.generic.Configuration
import sttp.tapir.{Codec, Schema, SchemaAnnotations, Validator}

import scala.collection.Factory
import scala.compiletime.*
import scala.deriving.Mirror
import scala.quoted.*
import scala.reflect.ClassTag
import scala.util.{Failure, NotGiven, Success, Try}

import macros.*
import scala.annotation.implicitNotFound

object Pickler:

  inline def derived[T: ClassTag](using Configuration, Mirror.Of[T]): Pickler[T] =
    given subtypeDiscriminator: SubtypeDiscriminator[T] = DefaultSubtypeDiscriminator()
    summonFrom {
      case schema: Schema[T] => fromExistingSchemaAndRw[T](schema)
      case _                 => buildNewPickler[T]()
    }

  inline def oneOfUsingField[T: ClassTag, V](extractor: T => V, asString: V => String)(
      mapping: (V, Pickler[_ <: T])*
  )(using m: Mirror.Of[T], c: Configuration, p: Pickler[V]): Pickler[T] =

    val paramExtractor = extractor
    val paramAsString = asString
    val paramMapping = mapping
    type ParamV = V
    given subtypeDiscriminator: SubtypeDiscriminator[T] = new CustomSubtypeDiscriminator[T] {
      type V = ParamV
      override def extractor = paramExtractor
      override def asString = paramAsString
      override lazy val mapping = paramMapping
    }
    summonFrom {
      case schema: Schema[T] => fromExistingSchemaAndRw[T](schema)
      case _ =>
        inline m match {
          case p: Mirror.ProductOf[T] =>
            error(
              s"Unexpected product type (case class) ${implicitly[ClassTag[T]].runtimeClass.getSimpleName()}, this method should only be used with sum types (like sealed hierarchy)"
            )
          case _: Mirror.SumOf[T] =>
            inline if (isScalaEnum[T])
              error("oneOfUsingField cannot be used with enums. Try Pickler.derivedEnumeration instead.")
            else {
              given schemaV: Schema[V] = p.schema
              val schema: Schema[T] = Schema.oneOfUsingField[T, V](extractor, asString)(
                mapping.toList.map { case (v, p) =>
                  (v, p.schema)
                }: _*
              )
              lazy val childPicklers: Tuple.Map[m.MirroredElemTypes, Pickler] = summonChildPicklerInstances[T, m.MirroredElemTypes]
              picklerSum(schema, childPicklers)
            }
        }
    }

  inline def derivedEnumeration[T: ClassTag](using Mirror.Of[T]): CreateDerivedEnumerationPickler[T] =
    inline erasedValue[T] match
      case _: Null =>
        error("Unexpected non-enum Null passed to derivedEnumeration")
      case _: Nothing =>
        error("Unexpected non-enum Nothing passed to derivedEnumeration")
      case _: reflect.Enum =>
        new CreateDerivedEnumerationPickler(Validator.derivedEnumeration[T], SchemaAnnotations.derived[T])
      case _ =>
        error("Unexpected non-enum type passed to derivedEnumeration")

  inline given nonMirrorPickler[T](using Configuration, NotGiven[Mirror.Of[T]]): Pickler[T] =
    summonFrom {
      // It turns out that summoning a Pickler can sometimes fall into this branch, even if we explicitly state that we wan't a NotGiven in the method signature
      case m: Mirror.Of[T] => errorForType[T]("Failed to summon a Pickler[%s]. Try using Pickler.derived or importing sttp.tapir.json.pickler.generic.auto.*")
      case n: NotGiven[Mirror.Of[T]] =>
        Pickler(
          new TapirPickle[T] {
            // Relying on given writers and readers provided by uPickle Writers and Readers base traits
            // They should take care of deriving for Int, String, Boolean, Option, List, Map, Array, etc.
            override lazy val reader = summonInline[Reader[T]]
            override lazy val writer = summonInline[Writer[T]]
          },
          summonInline[Schema[T]]
        )
    }

  given picklerForOption[T: Pickler](using Configuration, Mirror.Of[T]): Pickler[Option[T]] =
    summon[Pickler[T]].asOption

  given picklerForIterable[T: Pickler, C[X] <: Iterable[X]](using Configuration, Mirror.Of[T], Factory[T, C[T]]): Pickler[C[T]] =
    summon[Pickler[T]].asIterable[C]

  given picklerForEither[A, B](using pa: Pickler[A], pb: Pickler[B]): Pickler[Either[A, B]] =
    given Schema[A] = pa.schema
    given Schema[B] = pb.schema
    val newSchema = summon[Schema[Either[A, B]]]

    new Pickler[Either[A, B]](
      new TapirPickle[Either[A, B]] {
        given Reader[A] = pa.innerUpickle.reader.asInstanceOf[Reader[A]]
        given Writer[A] = pa.innerUpickle.writer.asInstanceOf[Writer[A]]
        given Reader[B] = pb.innerUpickle.reader.asInstanceOf[Reader[B]]
        given Writer[B] = pb.innerUpickle.writer.asInstanceOf[Writer[B]]
        override lazy val writer = summon[Writer[Either[A, B]]]
        override lazy val reader = summon[Reader[Either[A, B]]]
      },
      newSchema
    )

  given picklerForArray[T: Pickler: ClassTag]: Pickler[Array[T]] =
    summon[Pickler[T]].asArray

  inline given picklerForStringMap[V](using pv: Pickler[V]): Pickler[Map[String, V]] =
    given Schema[V] = pv.schema
    val newSchema = Schema.schemaForMap[V]
    new Pickler[Map[String, V]](
      new TapirPickle[Map[String, V]] {
        given Reader[V] = pv.innerUpickle.reader.asInstanceOf[Reader[V]]
        given Writer[V] = pv.innerUpickle.writer.asInstanceOf[Writer[V]]
        override lazy val writer = summon[Writer[Map[String, V]]]
        override lazy val reader = summon[Reader[Map[String, V]]]
      },
      newSchema
    )

  inline def picklerForMap[K, V](keyToString: K => String)(using pk: Pickler[K], pv: Pickler[V]): Pickler[Map[K, V]] =
    given Schema[V] = pv.schema
    val newSchema = Schema.schemaForMap[K, V](keyToString)
    new Pickler[Map[K, V]](
      new TapirPickle[Map[K, V]] {
        given Reader[K] = pk.innerUpickle.reader.asInstanceOf[Reader[K]]
        given Writer[K] = pk.innerUpickle.writer.asInstanceOf[Writer[K]]
        given Reader[V] = pv.innerUpickle.reader.asInstanceOf[Reader[V]]
        given Writer[V] = pv.innerUpickle.writer.asInstanceOf[Writer[V]]
        override lazy val writer = summon[Writer[Map[K, V]]]
        override lazy val reader = summon[Reader[Map[K, V]]]
      },
      newSchema
    )

  inline given picklerForAnyVal[T <: AnyVal]: Pickler[T] = ${ picklerForAnyValImpl[T] }

  private inline def errorForType[T](inline template: String): Null = ${ errorForTypeImpl[T]('template) }

  private def errorForTypeImpl[T: Type](template: Expr[String])(using Quotes): Expr[Null] = {
    import quotes.reflect.*
    val templateStr = template.valueOrAbort
    val typeName = TypeRepr.of[T].show
    report.error(String.format(templateStr, typeName))
    '{null}
  }

  private def picklerForAnyValImpl[T: Type](using quotes: Quotes): Expr[Pickler[T]] =
    import quotes.reflect.*
    val tpe = TypeRepr.of[T]

    val isValueCaseClass =
      tpe.typeSymbol.isClassDef && tpe.classSymbol.get.flags.is(Flags.Case) && tpe.baseClasses.contains(Symbol.classSymbol("scala.AnyVal"))

    if (!isValueCaseClass) {
      '{ nonMirrorPickler[T] }
    } else {

      val field = tpe.typeSymbol.declaredFields.head
      val fieldTpe = tpe.memberType(field)
      fieldTpe.asType match
        case '[f] =>
          val basePickler = Expr.summon[Pickler[f]].getOrElse {
            report.errorAndAbort(
              s"Cannot summon Pickler for value class ${tpe.show}. Missing Pickler[${fieldTpe.show}] in implicit scope."
            )
          }
          '{
            val newSchema: Schema[T] = ${ basePickler }.schema.as[T]
            new Pickler[T](
              new TapirPickle[T] {
                override lazy val writer = summonInline[Writer[f]].comap[T](
                  // writing object of type T means writing T.field
                  ccObj => ${ Select.unique(('ccObj).asTerm, field.name).asExprOf[f] }
                )
                // a reader of type f (field) will read it and wrap into value object using the consutructor of T
                override lazy val reader = summonInline[Reader[f]]
                  .map[T](fieldObj => ${ Apply(Select.unique(New(Inferred(tpe)), "<init>"), List(('fieldObj).asTerm)).asExprOf[T] })
              },
              newSchema
            )
          }
    }

  private inline def fromExistingSchemaAndRw[T](schema: Schema[T])(using ClassTag[T], Configuration, Mirror.Of[T]): Pickler[T] =
    Pickler(
      new TapirPickle[T] {
        val rw: ReadWriter[T] = summonFrom {
          case foundTapirRW: ReadWriter[T] =>
            foundTapirRW
          case foundUpickleDefaultRW: _root_.upickle.default.ReadWriter[T] => // there is BOTH schema and ReadWriter in scope
            foundUpickleDefaultRW.asInstanceOf[ReadWriter[T]]
          case _ =>
            errorForType[T](
              "Found implicit Schema[%s] but couldn't find a uPickle ReadWriter for this type. Either provide a ReadWriter, or remove the Schema from scope and let Pickler derive its own."
            )
            null
        }
        override lazy val reader = rw
        override lazy val writer = rw
      },
      schema
    )

  private[pickler] inline def buildNewPickler[T: ClassTag](
  )(using m: Mirror.Of[T], c: Configuration, subtypeDiscriminator: SubtypeDiscriminator[T]): Pickler[T] =
    // The lazy modifier is necessary for preventing infinite recursion in the derived instance for recursive types such as Lst
    lazy val childPicklers: Tuple.Map[m.MirroredElemTypes, Pickler] = summonChildPicklerInstances[T, m.MirroredElemTypes]
    inline m match {
      case p: Mirror.ProductOf[T] => picklerProduct(p, childPicklers)
      case _: Mirror.SumOf[T] =>
        val schema: Schema[T] =
          inline if (isScalaEnum[T])
            Schema.derivedEnumeration[T].defaultStringBased
          else
            Schema.derived[T]
        picklerSum(schema, childPicklers)
    }

  private[pickler] inline def summonChildPicklerInstances[T: ClassTag, Fields <: Tuple](using
      m: Mirror.Of[T],
      c: Configuration
  ): Tuple.Map[Fields, Pickler] =
    inline erasedValue[Fields] match {
      case _: (fieldType *: fieldTypesTail) =>
        val processedHead = deriveOrSummon[T, fieldType]
        val processedTail = summonChildPicklerInstances[T, fieldTypesTail]
        Tuple.fromArray((processedHead +: processedTail.toArray)).asInstanceOf[Tuple.Map[Fields, Pickler]]
      case _: EmptyTuple.type => EmptyTuple.asInstanceOf[Tuple.Map[Fields, Pickler]]
    }

  private inline def deriveOrSummon[T, FieldType](using Configuration): Pickler[FieldType] =
    inline erasedValue[FieldType] match
      case _: T => deriveRec[T, FieldType]
      case _    => summonFrom {
        case p: Pickler[FieldType] => p
        case _ => errorForType[FieldType]("Failed to summon Pickler[%s]. Try using Pickler.derived or importing sttp.tapir.json.pickler.generic.auto.*")
      }

  private inline def deriveRec[T, FieldType](using config: Configuration): Pickler[FieldType] =
    inline erasedValue[T] match
      case _: FieldType => error("Infinite recursive derivation")
      case _            => Pickler.derived[FieldType](using summonInline[ClassTag[FieldType]], config, summonInline[Mirror.Of[FieldType]])

      // Extract child RWs from child picklers
      // create a new RW from scratch using children rw and fields of the product
      // use provided existing schema
      // use data from schema to customize the new schema
  private inline def picklerProduct[T: ClassTag, TFields <: Tuple](
      product: Mirror.ProductOf[T],
      childPicklers: => Tuple.Map[TFields, Pickler]
  )(using
      config: Configuration,
      subtypeDiscriminator: SubtypeDiscriminator[T]
  ): Pickler[T] =
    lazy val derivedChildSchemas: Tuple.Map[TFields, Schema] =
      childPicklers.map([t] => (p: t) => p.asInstanceOf[Pickler[t]].schema).asInstanceOf[Tuple.Map[TFields, Schema]]
    val schema: Schema[T] = productSchema(derivedChildSchemas)
    // only now schema fields are enriched properly
    val enrichedChildSchemas = schema.schemaType.asInstanceOf[SProduct[T]].fields.map(_.schema)
    val childDefaults = enrichedChildSchemas.map(_.default.map(_._1))

    val tapirPickle = new TapirPickle[T] {
      override def tagName = config.discriminator.getOrElse(super.tagName)

      override lazy val writer: Writer[T] =
        macroProductW[T](
          schema,
          childPicklers.map([a] => (obj: a) => obj.asInstanceOf[Pickler[a]].innerUpickle.writer).productIterator.toList,
          childDefaults,
          subtypeDiscriminator
        )
      override lazy val reader: Reader[T] =
        macroProductR[T](schema, childPicklers.map([a] => (obj: a) => obj.asInstanceOf[Pickler[a]].innerUpickle.reader), childDefaults)(
          using product
        )

    }
    Pickler[T](tapirPickle, schema)

  private inline def productSchema[T, TFields <: Tuple](childSchemas: Tuple.Map[TFields, Schema])(using
      genericDerivationConfig: Configuration
  ): Schema[T] =
    SchemaDerivation.productSchema(genericDerivationConfig, childSchemas)

  private[tapir] inline def picklerSum[T: ClassTag, CP <: Tuple](schema: Schema[T], childPicklers: => CP)(using
      m: Mirror.Of[T],
      config: Configuration,
      subtypeDiscriminator: SubtypeDiscriminator[T]
  ): Pickler[T] =
    val tapirPickle = new TapirPickle[T] {
      override def tagName = config.discriminator.getOrElse(super.tagName)
      override lazy val writer: Writer[T] =
        macroSumW[T](
          schema,
          childPicklers.map([a] => (obj: a) => obj.asInstanceOf[Pickler[a]].innerUpickle.writer).productIterator.toList,
          subtypeDiscriminator
        )
      override lazy val reader: Reader[T] =
        macroSumR[T](childPicklers.map([a] => (obj: a) => obj.asInstanceOf[Pickler[a]].innerUpickle.reader), subtypeDiscriminator)

    }
    new Pickler[T](tapirPickle, schema)

@implicitNotFound("Failed to summon a Pickler. Try using Pickler[T].derived or importing sttp.tapir.json.pickler.generic.auto.*")
case class Pickler[T](innerUpickle: TapirPickle[T], schema: Schema[T]):

  def toCodec: JsonCodec[T] =
    import innerUpickle._
    given innerUpickle.Reader[T] = innerUpickle.reader
    given innerUpickle.Writer[T] = innerUpickle.writer
    given schemaT: Schema[T] = schema
    Codec.json[T] { s =>
      Try(read[T](s)) match {
        case Success(v) => Value(v)
        case Failure(e) => Error(s, JsonDecodeException(errors = List.empty, e))
      }
    } { t => write(t) }

  def asOption: Pickler[Option[T]] =
    val newSchema = schema.asOption
    new Pickler[Option[T]](
      new TapirPickle[Option[T]] {
        given Reader[T] = innerUpickle.reader.asInstanceOf[Reader[T]]
        given Writer[T] = innerUpickle.writer.asInstanceOf[Writer[T]]
        override lazy val writer = summon[Writer[Option[T]]]
        override lazy val reader = summon[Reader[Option[T]]]
      },
      newSchema
    )

  def asIterable[C[X] <: Iterable[X]](using Factory[T, C[T]]): Pickler[C[T]] =
    val newSchema = schema.asIterable[C]
    new Pickler[C[T]](
      new TapirPickle[C[T]] {
        given Reader[T] = innerUpickle.reader.asInstanceOf[Reader[T]]
        given Writer[T] = innerUpickle.writer.asInstanceOf[Writer[T]]
        override lazy val writer = summon[Writer[C[T]]]
        override lazy val reader = summon[Reader[C[T]]]
      },
      newSchema
    )

  def asArray(using ct: ClassTag[T]): Pickler[Array[T]] =
    val newSchema = schema.asArray
    new Pickler[Array[T]](
      new TapirPickle[Array[T]] {
        given Reader[T] = innerUpickle.reader.asInstanceOf[Reader[T]]
        given Writer[T] = innerUpickle.writer.asInstanceOf[Writer[T]]
        override lazy val writer = summon[Writer[Array[T]]]
        override lazy val reader = summon[Reader[Array[T]]]
      },
      newSchema
    )

given picklerToCodec[T](using p: Pickler[T]): JsonCodec[T] = p.toCodec
