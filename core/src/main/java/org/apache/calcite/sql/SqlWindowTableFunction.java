/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.sql;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.sql.type.ReturnTypes;
import org.apache.calcite.sql.type.SqlOperandTypeChecker;
import org.apache.calcite.sql.type.SqlReturnTypeInference;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.type.SqlTypeUtil;
import org.apache.calcite.sql.validate.SqlNameMatcher;
import org.apache.calcite.sql.validate.SqlValidator;

import java.util.List;

import static org.apache.calcite.util.Static.RESOURCE;

/**
 * Base class for a table-valued function that computes windows. Examples
 * include {@code TUMBLE}, {@code HOP} and {@code SESSION}.
 */
public class SqlWindowTableFunction extends SqlFunction
    implements SqlTableFunction {

  /** The data source which the table function computes with. */
  protected static final String PARAM_DATA = "DATA";

  /** The time attribute column. Also known as the event time. */
  protected static final String PARAM_TIMECOL = "TIMECOL";

  /** The window duration INTERVAL. */
  protected static final String PARAM_SIZE = "SIZE";

  /** The optional align offset for each window. */
  protected static final String PARAM_OFFSET = "OFFSET";

  /** The session key(s), only used for SESSION window. */
  protected static final String PARAM_KEY = "KEY";

  /** The slide interval, only used for HOP window. */
  protected static final String PARAM_SLIDE = "SLIDE";

  /**
   * Type-inference strategy whereby the row type of a table function call is a
   * ROW, which is combined from the row type of operand #0 (which is a TABLE)
   * and two additional fields. The fields are as follows:
   *
   * <ol>
   *  <li>{@code window_start}: TIMESTAMP type to indicate a window's start
   *  <li>{@code window_end}: TIMESTAMP type to indicate a window's end
   * </ol>
   */
  public static final SqlReturnTypeInference ARG0_TABLE_FUNCTION_WINDOWING =
      SqlWindowTableFunction::inferRowType;

  /** Creates a window table function with a given name. */
  public SqlWindowTableFunction(String name, SqlOperandTypeChecker operandTypeChecker) {
    super(name, SqlKind.OTHER_FUNCTION, ReturnTypes.CURSOR, null,
        operandTypeChecker, SqlFunctionCategory.SYSTEM);
  }

  @Override public SqlReturnTypeInference getRowTypeInference() {
    return ARG0_TABLE_FUNCTION_WINDOWING;
  }

  protected static boolean throwValidationSignatureErrorOrReturnFalse(SqlCallBinding callBinding,
      boolean throwOnFailure) {
    if (throwOnFailure) {
      throw callBinding.newValidationSignatureError();
    } else {
      return false;
    }
  }

  /**
   * Validate the heading operands are in the form:
   * (ROW, DESCRIPTOR, DESCRIPTOR ..., other params).
   *
   * @param callBinding The call binding
   * @param descriptors The number of descriptors following the first operand (e.g. the table)
   *
   * @return true if validation passes
   */
  protected static boolean validateTableWithFollowingDescriptors(
      SqlCallBinding callBinding, int descriptors) {
    final SqlNode operand0 = callBinding.operand(0);
    final SqlValidator validator = callBinding.getValidator();
    final RelDataType type = validator.getValidatedNodeType(operand0);
    if (type.getSqlTypeName() != SqlTypeName.ROW) {
      return false;
    }
    for (int i = 1; i < descriptors + 1; i++) {
      final SqlNode operand = callBinding.operand(i);
      if (operand.getKind() != SqlKind.DESCRIPTOR) {
        return false;
      }
      validateColumnNames(validator, type.getFieldNames(), ((SqlCall) operand).getOperandList());
    }
    return true;
  }

  /**
   * Validate the operands starting from position {@code startPos} are all INTERVAL.
   *
   * @param callBinding The call binding
   * @param startPos    The start position to validate (starting index is 0)
   *
   * @return true if validation passes
   */
  protected static boolean validateTailingIntervals(SqlCallBinding callBinding, int startPos) {
    final SqlValidator validator = callBinding.getValidator();
    for (int i = startPos; i < callBinding.getOperandCount(); i++) {
      final RelDataType type = validator.getValidatedNodeType(callBinding.operand(i));
      if (!SqlTypeUtil.isInterval(type)) {
        return false;
      }
    }
    return true;
  }

  private static void validateColumnNames(SqlValidator validator,
      List<String> fieldNames, List<SqlNode> columnNames) {
    final SqlNameMatcher matcher = validator.getCatalogReader().nameMatcher();
    for (SqlNode columnName : columnNames) {
      final String name = ((SqlIdentifier) columnName).getSimple();
      if (matcher.indexOf(fieldNames, name) < 0) {
        throw SqlUtil.newContextException(columnName.getParserPosition(),
            RESOURCE.unknownIdentifier(name));
      }
    }
  }

  /**
   * {@inheritDoc}
   *
   * <p>Overrides because the first parameter of
   * table-value function windowing is an explicit TABLE parameter,
   * which is not scalar.
   */
  @Override public boolean argumentMustBeScalar(int ordinal) {
    return ordinal != 0;
  }

  /** Helper for {@link #ARG0_TABLE_FUNCTION_WINDOWING}. */
  private static RelDataType inferRowType(SqlOperatorBinding opBinding) {
    final RelDataType inputRowType = opBinding.getOperandType(0);
    final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
    final RelDataType timestampType =
        typeFactory.createSqlType(SqlTypeName.TIMESTAMP);
    return typeFactory.builder()
        .kind(inputRowType.getStructKind())
        .addAll(inputRowType.getFieldList())
        .add("window_start", timestampType)
        .add("window_end", timestampType)
        .build();
  }
}
