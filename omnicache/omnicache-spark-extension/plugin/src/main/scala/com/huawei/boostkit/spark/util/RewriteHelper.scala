/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.huawei.boostkit.spark.util

import com.google.common.collect.{ArrayListMultimap, BiMap, HashBiMap, Multimap}
import com.huawei.boostkit.spark.conf.OmniCachePluginConfig
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import org.apache.spark.sql.catalyst.catalog.{CatalogTable, HiveTableRelation}
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.execution.datasources.LogicalRelation
import org.apache.spark.sql.internal.SQLConf

class RewriteHelper extends PredicateHelper {

  val SESSION_CATALOG_NAME: String = "spark_catalog"

  val EMPTY_BITMAP: HashBiMap[String, String] = HashBiMap.create[String, String]()
  val EMPTY_MAP: Map[ExpressionEqual,
      mutable.Set[ExpressionEqual]] = Map[ExpressionEqual, mutable.Set[ExpressionEqual]]()
  val EMPTY_MULTIMAP: Multimap[Int, Int] = ArrayListMultimap.create[Int, Int]

  /**
   * Rewrite [[EqualTo]] and [[EqualNullSafe]] operator to keep order. The following cases will be
   * equivalent:
   * 1. (a = b), (b = a);
   * 2. (a <=> b), (b <=> a).
   */
  private def rewriteEqual(condition: Expression): Expression = condition match {
    case eq@EqualTo(l: Expression, r: Expression) =>
      Seq(l, r).sortBy(hashCode).reduce(EqualTo)
    case eq@EqualNullSafe(l: Expression, r: Expression) =>
      Seq(l, r).sortBy(hashCode).reduce(EqualNullSafe)
    case _ => condition // Don't reorder.
  }

  def hashCode(_ar: Expression): Int = {
    // See http://stackoverflow.com/questions/113511/hash-code-implementation
    _ar match {
      case ar@AttributeReference(_, _, _, _) =>
        var h = 17
        h = h * 37 + ar.name.hashCode()
        h = h * 37 + ar.dataType.hashCode()
        h = h * 37 + ar.nullable.hashCode()
        h = h * 37 + ar.metadata.hashCode()
        h = h * 37 + ar.exprId.hashCode()
        h
      case _ => _ar.hashCode()
    }

  }

  /**
   * Normalizes plans:
   * - Filter the filter conditions that appear in a plan. For instance,
   * ((expr 1 && expr 2) && expr 3), (expr 1 && expr 2 && expr 3), (expr 3 && (expr 1 && expr 2)
   * etc., will all now be equivalent.
   * - Sample the seed will replaced by 0L.
   * - Join conditions will be resorted by hashCode.
   *
   * we use new hash function to avoid `ar.qualifier` from alias affect the final order.
   *
   */
  protected def normalizePlan(plan: LogicalPlan): LogicalPlan = {
    plan transform {
      case Filter(condition: Expression, child: LogicalPlan) =>
        Filter(splitConjunctivePredicates(condition).map(rewriteEqual).sortBy(hashCode)
            .reduce(And), child)
      case sample: Sample =>
        sample.copy(seed = 0L)
      case Join(left, right, joinType, condition, hint) if condition.isDefined =>
        val newCondition =
          splitConjunctivePredicates(condition.get).map(rewriteEqual).sortBy(hashCode)
              .reduce(And)
        Join(left, right, joinType, Some(newCondition), hint)
    }
  }

  def mergeConjunctiveExpressions(e: Seq[Expression]): Expression = {
    if (e.isEmpty) {
      return Literal.TrueLiteral
    }
    if (e.size == 1) {
      return e.head
    }
    e.reduce { (a, b) =>
      And(a, b)
    }
  }

  def fillQualifier(logicalPlan: LogicalPlan,
      exprIdToQualifier: mutable.HashMap[ExprId, AttributeReference]): LogicalPlan = {
    val newLogicalPlan = logicalPlan.transform {
      case plan =>
        plan.transformExpressions {
          case a: AttributeReference =>
            if (exprIdToQualifier.contains(a.exprId)) {
              exprIdToQualifier(a.exprId)
            } else {
              a
            }
          case a => a
        }
    }
    newLogicalPlan
  }

  def mapTablePlanAttrToQuery(viewTablePlan: LogicalPlan,
      viewQueryPlan: LogicalPlan): LogicalPlan = {
    // map by index
    val topProjectList: Seq[NamedExpression] = viewQueryPlan match {
      case Project(projectList, _) =>
        projectList
      case Aggregate(_, aggregateExpressions, _) =>
        aggregateExpressions
      case other =>
        other.output
    }
    val exprIdToQualifier = mutable.HashMap[ExprId, AttributeReference]()
    for ((project, column) <- topProjectList.zip(viewTablePlan.output)) {
      project match {
        // only map attr
        case _@Alias(attr@AttributeReference(_, _, _, _), _) =>
          exprIdToQualifier += (column.exprId -> attr)
        case a@AttributeReference(_, _, _, _) =>
          exprIdToQualifier += (column.exprId -> a)
        // skip function
        case _ =>
      }
    }
    fillQualifier(viewTablePlan, exprIdToQualifier)
  }

  def extractTopProjectList(logicalPlan: LogicalPlan): Seq[Expression] = {
    val topProjectList: Seq[Expression] = logicalPlan match {
      case Project(projectList, _) => projectList
      case Aggregate(_, aggregateExpressions, _) => aggregateExpressions
    }
    topProjectList
  }

  // TODO delete
  case class EquivalenceClasses() {

    def getEquivalenceClassesMap: Map[ExpressionEqual, mutable.Set[ExpressionEqual]] = {
      Map.empty
    }

    def getEquivalenceClasses: List[mutable.Set[ExpressionEqual]] = {
      List.empty
    }

    def addEquivalenceClass(p: ExpressionEqual, p2: ExpressionEqual): Unit = {

    }

  }

  def extractPredictExpressions(logicalPlan: LogicalPlan,
      tableMappings: BiMap[String, String])
  : (EquivalenceClasses, Seq[ExpressionEqual], Seq[ExpressionEqual]) = {
    var conjunctivePredicates: Seq[Expression] = Seq()
    var equiColumnPreds: mutable.Buffer[Expression] = ArrayBuffer()
    val rangePreds: mutable.Buffer[ExpressionEqual] = ArrayBuffer()
    val residualPreds: mutable.Buffer[ExpressionEqual] = ArrayBuffer()
    val normalizedPlan = normalizePlan(ExprSimplifier.simplify(logicalPlan))
    normalizedPlan foreach {
      case Filter(condition, _) =>
        conjunctivePredicates ++= splitConjunctivePredicates(condition)
      case Join(_, _, _, condition, _) =>
        if (condition.isDefined) {
          conjunctivePredicates ++= splitConjunctivePredicates(condition.get)
        }
      case _ =>
    }
    for (e <- conjunctivePredicates) {
      if (e.isInstanceOf[EqualTo]) {
        val left = e.asInstanceOf[EqualTo].left
        val right = e.asInstanceOf[EqualTo].right
        if (ExprOptUtil.isReference(left, allowCast = false)
            && ExprOptUtil.isReference(right, allowCast = false)) {
          equiColumnPreds += e
        } else if ((ExprOptUtil.isReference(left, allowCast = false)
            && ExprOptUtil.isConstant(right))
            || (ExprOptUtil.isReference(right, allowCast = false)
            && ExprOptUtil.isConstant(left))) {
          rangePreds += ExpressionEqual(e)
        } else {
          residualPreds += ExpressionEqual(e)
        }
      } else if (e.isInstanceOf[LessThan] || e.isInstanceOf[GreaterThan]
          || e.isInstanceOf[LessThanOrEqual] || e.isInstanceOf[GreaterThanOrEqual]) {
        val left = e.asInstanceOf[BinaryComparison].left
        val right = e.asInstanceOf[BinaryComparison].right
        if ((ExprOptUtil.isReference(left, allowCast = false)
            && ExprOptUtil.isConstant(right))
            || (ExprOptUtil.isReference(right, allowCast = false)
            && ExprOptUtil.isConstant(left))) {
          rangePreds += ExpressionEqual(e)
        } else {
          residualPreds += ExpressionEqual(e)
        }
      } else {
        residualPreds += ExpressionEqual(e)
      }
    }
    equiColumnPreds = swapTableReferences(equiColumnPreds, tableMappings)
    val equivalenceClasses: EquivalenceClasses = EquivalenceClasses()
    for (i <- equiColumnPreds.indices) {
      val left = equiColumnPreds(i).asInstanceOf[EqualTo].left
      val right = equiColumnPreds(i).asInstanceOf[EqualTo].right
      equivalenceClasses.addEquivalenceClass(ExpressionEqual(left), ExpressionEqual(right))
    }
    (equivalenceClasses, rangePreds, residualPreds)
  }

  def extractTables(logicalPlan: LogicalPlan): (LogicalPlan, Set[TableEqual]) = {
    // tableName->duplicateIndex,start from 0
    val qualifierToIdx = mutable.HashMap.empty[String, Int]
    // logicalPlan->(tableName,duplicateIndex)
    val tablePlanToIdx = mutable.HashMap.empty[LogicalPlan, (String, Int, String)]
    // exprId->AttributeReference,use this to replace LogicalPlan's attr
    val exprIdToAttr = mutable.HashMap.empty[ExprId, AttributeReference]

    val addIdxAndAttrInfo = (catalogTable: CatalogTable, logicalPlan: LogicalPlan,
        attrs: Seq[AttributeReference]) => {
      val table = catalogTable.identifier.toString()
      val idx = qualifierToIdx.getOrElse(table, -1) + 1
      qualifierToIdx += (table -> idx)
      tablePlanToIdx += (logicalPlan -> (table,
          idx, Seq(SESSION_CATALOG_NAME, catalogTable.database,
        catalogTable.identifier.table, String.valueOf(idx)).mkString(".")))
      attrs.foreach { attr =>
        val newAttr = attr.copy()(exprId = attr.exprId, qualifier =
          Seq(SESSION_CATALOG_NAME, catalogTable.database,
            catalogTable.identifier.table, String.valueOf(idx)))
        exprIdToAttr += (attr.exprId -> newAttr)
      }
    }

    logicalPlan.foreachUp {
      case h@HiveTableRelation(tableMeta, _, _, _, _) =>
        addIdxAndAttrInfo(tableMeta, h, h.output)
      case h@LogicalRelation(_, _, catalogTable, _) =>
        if (catalogTable.isDefined) {
          addIdxAndAttrInfo(catalogTable.get, h, h.output)
        }
      case _ =>
    }

    val mappedTables = tablePlanToIdx.keySet.map { tablePlan =>
      val (tableName, idx, qualifier) = tablePlanToIdx(tablePlan)
      TableEqual(tableName, "%s.%d".format(tableName, idx),
        qualifier, fillQualifier(tablePlan, exprIdToAttr))
    }.toSet
    val mappedQuery = fillQualifier(logicalPlan, exprIdToAttr)
    (mappedQuery, mappedTables)
  }

  def swapTableColumnReferences[T <: Iterable[Expression]](expression: T,
      tableMapping: BiMap[String, String],
      columnMapping: Map[ExpressionEqual,
          mutable.Set[ExpressionEqual]]): T = {
    var result: T = expression
    if (!tableMapping.isEmpty) {
      result = result.map { expr =>
        expr.transform {
          case a: AttributeReference =>
            val key = a.qualifier.mkString(".")
            if (tableMapping.containsKey(key)) {
              val newQualifier = tableMapping.get(key).split(".").toSeq
              a.copy()(exprId = a.exprId, qualifier = newQualifier)
            } else {
              a
            }
          case e => e
        }
      }.asInstanceOf[T]
    }
    if (columnMapping.nonEmpty) {
      result = result.map { expr =>
        expr.transform {
          case e: NamedExpression =>
            val expressionEqual = ExpressionEqual(e)
            if (columnMapping.contains(expressionEqual)) {
              val newAttr = columnMapping(expressionEqual)
                  .head.expression.asInstanceOf[NamedExpression]
              newAttr
            } else {
              e
            }
          case e => e
        }
      }.asInstanceOf[T]
    }
    result
  }

  def swapColumnTableReferences[T <: Iterable[Expression]](expression: T,
      tableMapping: BiMap[String, String],
      columnMapping: Map[ExpressionEqual,
          mutable.Set[ExpressionEqual]]): T = {
    var result = swapTableColumnReferences(expression, EMPTY_BITMAP, columnMapping)
    result = swapTableColumnReferences(result, tableMapping, EMPTY_MAP)
    result
  }

  def swapTableReferences[T <: Iterable[Expression]](expression: T,
      tableMapping: BiMap[String, String]): T = {
    swapTableColumnReferences(expression, tableMapping, EMPTY_MAP)
  }

  def swapColumnReferences[T <: Iterable[Expression]](expression: T,
      columnMapping: Map[ExpressionEqual,
          mutable.Set[ExpressionEqual]]): T = {
    swapTableColumnReferences(expression, EMPTY_BITMAP, columnMapping)
  }
}

object RewriteHelper {
  def canonicalize(expression: Expression): Expression = {
    val canonicalizedChildren = expression.children.map(RewriteHelper.canonicalize)
    expressionReorder(expression.withNewChildren(canonicalizedChildren))
  }

  /** Collects adjacent commutative operations. */
  private def gatherCommutative(
      e: Expression,
      f: PartialFunction[Expression, Seq[Expression]]): Seq[Expression] = e match {
    case c if f.isDefinedAt(c) => f(c).flatMap(gatherCommutative(_, f))
    case other => other :: Nil
  }

  /** Orders a set of commutative operations by their hash code. */
  private def orderCommutative(
      e: Expression,
      f: PartialFunction[Expression, Seq[Expression]]): Seq[Expression] =
    gatherCommutative(e, f).sortBy(_.hashCode())

  /** Rearrange expressions that are commutative or associative. */
  private def expressionReorder(e: Expression): Expression = e match {
    case a@Add(_, _, f) =>
      orderCommutative(a, { case Add(l, r, _) => Seq(l, r) }).reduce(Add(_, _, f))
    case m@Multiply(_, _, f) =>
      orderCommutative(m, { case Multiply(l, r, _) => Seq(l, r) }).reduce(Multiply(_, _, f))

    case o: Or =>
      orderCommutative(o, { case Or(l, r) if l.deterministic && r.deterministic => Seq(l, r) })
          .reduce(Or)
    case a: And =>
      orderCommutative(a, { case And(l, r) if l.deterministic && r.deterministic => Seq(l, r) })
          .reduce(And)

    case o: BitwiseOr =>
      orderCommutative(o, { case BitwiseOr(l, r) => Seq(l, r) }).reduce(BitwiseOr)
    case a: BitwiseAnd =>
      orderCommutative(a, { case BitwiseAnd(l, r) => Seq(l, r) }).reduce(BitwiseAnd)
    case x: BitwiseXor =>
      orderCommutative(x, { case BitwiseXor(l, r) => Seq(l, r) }).reduce(BitwiseXor)

    case EqualTo(l, r) if l.hashCode() > r.hashCode() => EqualTo(r, l)
    case EqualNullSafe(l, r) if l.hashCode() > r.hashCode() => EqualNullSafe(r, l)

    case GreaterThan(l, r) if l.hashCode() > r.hashCode() => LessThan(r, l)
    case LessThan(l, r) if l.hashCode() > r.hashCode() => GreaterThan(r, l)

    case GreaterThanOrEqual(l, r) if l.hashCode() > r.hashCode() => LessThanOrEqual(r, l)
    case LessThanOrEqual(l, r) if l.hashCode() > r.hashCode() => GreaterThanOrEqual(r, l)

    // Note in the following `NOT` cases, `l.hashCode() <= r.hashCode()` holds. The reason is that
    // canonicalization is conducted bottom-up -- see [[Expression.canonicalized]].
    case Not(GreaterThan(l, r)) => LessThanOrEqual(l, r)
    case Not(LessThan(l, r)) => GreaterThanOrEqual(l, r)
    case Not(GreaterThanOrEqual(l, r)) => LessThan(l, r)
    case Not(LessThanOrEqual(l, r)) => GreaterThan(l, r)

    // order the list in the In operator
    case In(value, list) if list.length > 1 => In(value, list.sortBy(_.hashCode()))

    case g: Greatest =>
      val newChildren = orderCommutative(g, { case Greatest(children) => children })
      Greatest(newChildren)
    case l: Least =>
      val newChildren = orderCommutative(l, { case Least(children) => children })
      Least(newChildren)

    case _ => e
  }

  def extractAllAttrsFromExpression(expressions: Seq[Expression]): Set[AttributeReference] = {
    var attrs = Set[AttributeReference]()
    expressions.foreach { e =>
      e.foreach {
        case a@AttributeReference(_, _, _, _) =>
          attrs += a
        case _ =>
      }
    }
    attrs
  }

  def containsMV(logicalPlan: LogicalPlan): Boolean = {
    logicalPlan.foreachUp {
      case _@HiveTableRelation(tableMeta, _, _, _, _) =>
        if (OmniCachePluginConfig.isMV(tableMeta)) {
          return true
        }
      case _@LogicalRelation(_, _, catalogTable, _) =>
        if (catalogTable.isDefined) {
          if (OmniCachePluginConfig.isMV(catalogTable.get)) {
            return true
          }
        }
      case _ =>
    }
    false
  }

  def enableCachePlugin(): Unit = {
    SQLConf.get.setConfString("spark.sql.omnicache.enable", "true")
  }

  def disableCachePlugin(): Unit = {
    SQLConf.get.setConfString("spark.sql.omnicache.enable", "false")
  }
}

case class ExpressionEqual(expression: Expression) {
  // like org.apache.spark.sql.catalyst.expressions.EquivalentExpressions.Expr
  lazy val realExpr: Expression = RewriteHelper.canonicalize(extractRealExpr(expression))
  lazy val sql: String = realExpr.sql

  override def equals(obj: Any): Boolean = obj match {
    case e: ExpressionEqual => sql == e.sql
    case _ => false
  }

  override def hashCode(): Int = sql.hashCode()

  def extractRealExpr(expression: Expression): Expression = expression match {
    case Alias(child, _) => extractRealExpr(child)
    case other => other
  }
}

case class TableEqual(tableName: String, tableNameWithIdx: String,
    qualifier: String, logicalPlan: LogicalPlan) {

  override def equals(obj: Any): Boolean = obj match {
    case other: TableEqual => tableNameWithIdx == other.tableNameWithIdx
    case _ => false
  }

  override def hashCode(): Int = tableNameWithIdx.hashCode()
}

// TODO delete
object ExprSimplifier {
  def simplify(logicalPlan: LogicalPlan): LogicalPlan = {
    logicalPlan
  }

  def simplify(expr: Expression): Expression = {
    expr
  }
}

// TODO delete
object ExprOptUtil {
  def isReference(expr: Expression, allowCast: Boolean): Boolean = {
    true
  }

  def isConstant(expr: Expression): Boolean = {
    true
  }

  def disjunctions(expr: Expression): mutable.Buffer[Expression] = {
    null
  }

  def conjunctions(expr: Expression): mutable.Buffer[Expression] = {
    null
  }

  def decomposeConjunctions(expr: Expression,
      terms: mutable.Buffer[Expression],
      notTerms: mutable.Buffer[Expression]): Unit = {
  }

  def composeConjunctions(terms: Seq[Expression], nullOnEmpty: Boolean): Expression = {
    null
  }

  def isAlwaysFalse(expr: Expression): Boolean = {
    true
  }
}