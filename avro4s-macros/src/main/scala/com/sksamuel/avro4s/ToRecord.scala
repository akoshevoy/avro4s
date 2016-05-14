package com.sksamuel.avro4s

import java.nio.ByteBuffer

import org.apache.avro.generic.{GenericData, GenericRecord}
import shapeless.Lazy

import scala.collection.JavaConverters._
import scala.language.experimental.macros
import scala.language.implicitConversions

trait ToValue[A] {
  def apply(value: A): Any = value
}

trait LowPriorityToValue {
  implicit def GenericWriter[T](implicit writer: ToRecord[T]): ToValue[T] = new ToValue[T] {
    override def apply(value: T): GenericRecord = writer(value)
  }
}

object ToValue extends LowPriorityToValue {

  implicit object BooleanToValue extends ToValue[Boolean]

  implicit object StringToValue extends ToValue[String]

  implicit object DoubleToValue extends ToValue[Double]

  implicit object FloatToValue extends ToValue[Float]

  implicit object IntToValue extends ToValue[Int]

  implicit object LongToValue extends ToValue[Long]

  implicit object BigDecimalToValue extends ToValue[BigDecimal] {
    override def apply(value: BigDecimal): ByteBuffer = ByteBuffer.wrap(value.toString.getBytes)
  }

  implicit def ListToValue[T](implicit tovalue: ToValue[T]): ToValue[List[T]] = new ToValue[List[T]] {
    override def apply(values: List[T]): Any = values.map(tovalue.apply).asJava
  }

  implicit def SetToValue[T](implicit tovalue: ToValue[T]): ToValue[Set[T]] = new ToValue[Set[T]] {
    override def apply(values: Set[T]): Any = values.map(tovalue.apply).asJava
  }

  implicit def SeqToValue[T](implicit tovalue: ToValue[T]): ToValue[Seq[T]] = new ToValue[Seq[T]] {
    override def apply(values: Seq[T]): Any = values.map(tovalue.apply).asJava
  }

  implicit def OptionToValue[T](implicit tovalue: ToValue[T]) = new ToValue[Option[T]] {
    override def apply(value: Option[T]): Any = value.map(tovalue.apply).orNull
  }

  implicit def ArrayToValue[T](implicit tovalue: ToValue[T]): ToValue[Array[T]] = new ToValue[Array[T]] {
    override def apply(value: Array[T]): Any = value.headOption match {
      case Some(b: Byte) => ByteBuffer.wrap(value.asInstanceOf[Array[Byte]])
      case _ => value.map(tovalue.apply).toSeq.asJavaCollection
    }
  }

  implicit object ByteArrayToValue extends ToValue[Array[Byte]] {
    override def apply(value: Array[Byte]): ByteBuffer = ByteBuffer.wrap(value)
  }

  implicit def MapToValue[T](implicit tovalue: ToValue[T]) = new ToValue[Map[String, T]] {
    override def apply(value: Map[String, T]): java.util.Map[String, T] = {
      value.mapValues(tovalue.apply).asInstanceOf[Map[String, T]].asJava
    }
  }

  implicit def JavaEnumToValue[E <: Enum[_]]: ToValue[E] = new ToValue[E] {
    override def apply(value: E): Any = value.name
  }

  implicit def ScalaEnumToValue[E <: Enumeration#Value]: ToValue[E] = new ToValue[E] {
    override def apply(value: E): Any = value.toString
  }

  implicit def EitherToValue[T, U](implicit lefttovalue: ToValue[T], righttovalue: ToValue[U]) = new ToValue[Either[T, U]] {
    override def apply(value: Either[T, U]): Any = value match {
      case Left(left) => lefttovalue(left)
      case Right(right) => righttovalue(right)
    }
  }
}

trait ToRecord[T] {
  def apply(t: T): GenericRecord
}

object ToRecord {

  implicit def apply[T]: ToRecord[T] = macro applyImpl[T]

  def applyImpl[T: c.WeakTypeTag](c: scala.reflect.macros.whitebox.Context): c.Expr[ToRecord[T]] = {
    import c.universe._
    val tpe = weakTypeTag[T].tpe

    def fieldsForType(tpe: c.universe.Type): List[c.universe.Symbol] = {
      tpe.decls.collectFirst {
        case m: MethodSymbol if m.isPrimaryConstructor => m
      }.flatMap(_.paramLists.headOption).getOrElse(Nil)
    }

    val tuples: Seq[Tree] = fieldsForType(tpe).map { f =>
      val name = f.name.asInstanceOf[c.TermName]
      val mapKey: String = name.decodedName.toString
      val sig = f.typeSignature
      q"""{
            import com.sksamuel.avro4s.ToSchema._
            import com.sksamuel.avro4s.ToValue._
            import com.sksamuel.avro4s.SchemaFor._
            com.sksamuel.avro4s.ToRecord.tuple[$sig]($mapKey, t.$name : $sig)
          }
       """
    }

    c.Expr[ToRecord[T]](
      q"""new com.sksamuel.avro4s.ToRecord[$tpe] {
            def apply(t : $tpe): org.apache.avro.generic.GenericRecord = {
              val map: Map[String, Any] = Map(..$tuples)
              com.sksamuel.avro4s.ToRecord.createRecord[$tpe](map)
            }
          }
        """
    )
  }

  def tuple[T](name: String, value: T)(implicit toValue: Lazy[ToValue[T]]): (String, Any) = name -> toValue.value(value)

  def createRecord[T](map: Map[String, Any])
                     (implicit schemaFor: SchemaFor[T]): GenericRecord = {
    val record = new GenericData.Record(schemaFor())
    map.foreach { case (key, value) => record.put(key, value) }
    record
  }
}