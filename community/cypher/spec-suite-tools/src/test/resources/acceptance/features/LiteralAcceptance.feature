#
# Copyright (c) "Neo4j"
# Neo4j Sweden AB [http://neo4j.com]
#
# This file is part of Neo4j.
#
# Neo4j is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <http://www.gnu.org/licenses/>.
#

#encoding: utf-8

Feature: LiteralAcceptance

  Scenario: [1] Return a positive integer with underscore
    Given any graph
    When executing query:
      """
      RETURN 4_2 AS literal
      """
    Then the result should be, in any order:
      | literal |
      | 42      |
    And no side effects

  Scenario: [2] Return a negative integer with underscore
    Given any graph
    When executing query:
      """
      RETURN -4_2 AS literal
      """
    Then the result should be, in any order:
      | literal |
      | -42      |
    And no side effects

  Scenario: [3] Return a positive integer with multiple underscores
    Given any graph
    When executing query:
      """
      RETURN 1_0_0_0 AS literal
      """
    Then the result should be, in any order:
      | literal |
      | 1000    |
    And no side effects

  @skipGrammarCheck
  Scenario: [4] Fail on an integer containing consecutive underscores
    Given any graph
    When executing query:
      """
      RETURN 1__000 AS literal
      """
    Then a SyntaxError should be raised at compile time: InvalidNumberLiteral

  @skipGrammarCheck
  Scenario: [5] Fail on an integer starting with underscore
    Given any graph
    When executing query:
      """
      RETURN _1000 AS literal
      """
    Then a SyntaxError should be raised at compile time: UndefinedVariable

  @skipGrammarCheck
  Scenario: [6] Fail on an integer ending with underscore
    Given any graph
    When executing query:
      """
      RETURN 1000_ AS literal
      """
    Then a SyntaxError should be raised at compile time: InvalidNumberLiteral

  Scenario: [7] Return a positive float with underscore
    Given any graph
    When executing query:
      """
      RETURN 1_000.000_1 AS literal
      """
    Then the result should be, in any order:
      | literal   |
      | 1000.0001 |
    And no side effects

  Scenario: [8] Return a negative float with underscore
    Given any graph
    When executing query:
      """
      RETURN -1_0.0_1 AS literal
      """
    Then the result should be, in any order:
      | literal |
      | -10.01  |
    And no side effects

  Scenario: [9] Return a positive float with multiple underscores
    Given any graph
    When executing query:
      """
      RETURN 1_0_0_0.0_0_0_1 AS literal
      """
    Then the result should be, in any order:
      | literal   |
      | 1000.0001 |
    And no side effects

  Scenario: [10] Return a positive float with underscore in the exponent
    Given any graph
    When executing query:
      """
      RETURN 1.0E1_0 AS literal
      """
    Then the result should be, in any order:
      | literal |
      | 1.0E10  |
    And no side effects

  @skipGrammarCheck
  Scenario: [11] Fail on a float containing consecutive underscores
    Given any graph
    When executing query:
      """
      RETURN 1.00__01 AS literal
      """
    Then a SyntaxError should be raised at compile time: InvalidNumberLiteral

  @skipGrammarCheck
  Scenario: [12] Fail on a float with underscore before decimal separator
    Given any graph
    When executing query:
      """
      RETURN 1_.0001 AS literal
      """
    Then a SyntaxError should be raised at compile time: InvalidNumberLiteral

  @skipGrammarCheck
  Scenario: [13] Fail on a float with underscore following decimal separator
    Given any graph
    When executing query:
      """
      RETURN 1._0001 AS literal
      """
    Then a SyntaxError should be raised at compile time: InvalidNumberLiteral

  Scenario: [14] Return a positive hexadecimal number with underscore
    Given any graph
    When executing query:
      """
      RETURN 0x_2A_40_7F AS literal
      """
    Then the result should be, in any order:
      | literal |
      | 2769023 |
    And no side effects

  Scenario: [15] Return a negative hexadecimal number with underscore
    Given any graph
    When executing query:
      """
      RETURN -0x_2A_40_7F AS literal
      """
    Then the result should be, in any order:
      | literal |
      | -2769023 |
    And no side effects

  @skipGrammarCheck
  Scenario: [16] Fail on a hexadecimal number with underscore in prefix
    Given any graph
    When executing query:
      """
      RETURN 0_xFF AS literal
      """
    Then a SyntaxError should be raised at compile time: InvalidNumberLiteral

  @skipGrammarCheck
  Scenario: [17] Fail on a hexadecimal number starting with underscore
    Given any graph
    When executing query:
      """
      RETURN _0x2A AS literal
      """
    Then a SyntaxError should be raised at compile time: UndefinedVariable

  @skipGrammarCheck
  Scenario: [18] Fail on a hexadecimal number ending with underscore
    Given any graph
    When executing query:
      """
      RETURN 0x2A_ AS literal
      """
    Then a SyntaxError should be raised at compile time: InvalidNumberLiteral

  @skipGrammarCheck
  Scenario: [19] Fail on a hexadecimal number containing consecutive underscores
    Given any graph
    When executing query:
      """
      RETURN 0x2__A AS literal
      """
    Then a SyntaxError should be raised at compile time: InvalidNumberLiteral

  Scenario: [20] Return a positive octal number with underscore
    Given any graph
    When executing query:
      """
      RETURN 0o_66_44_22 AS literal
      """
    Then the result should be, in any order:
      | literal |
      | 223506  |
    And no side effects

  Scenario: [21] Return a negative octal number with underscore
    Given any graph
    When executing query:
      """
      RETURN -0o_66_44_22 AS literal
      """
    Then the result should be, in any order:
      | literal |
      | -223506  |
    And no side effects

  @skipGrammarCheck
  Scenario: [22] Fail on an deprecated octal number syntax with underscore
    Given any graph
    When executing query:
      """
      RETURN 0_66_44_22 AS literal
      """
    Then a SyntaxError should be raised at compile time: InvalidNumberLiteral

  @skipGrammarCheck
  Scenario: [23] Fail on an octal number with underscore in prefix
    Given any graph
    When executing query:
      """
      RETURN 0_o77 AS literal
      """
    Then a SyntaxError should be raised at compile time: InvalidNumberLiteral

  @skipGrammarCheck
  Scenario: [24] Fail on an octal number starting with underscore
    Given any graph
    When executing query:
      """
      RETURN _0o77 AS literal
      """
    Then a SyntaxError should be raised at compile time: UndefinedVariable

  @skipGrammarCheck
  Scenario: [25] Fail on an octal number ending with underscore
    Given any graph
    When executing query:
      """
      RETURN 0o77_ AS literal
      """
    Then a SyntaxError should be raised at compile time: InvalidNumberLiteral

  @skipGrammarCheck
  Scenario: [25] Fail on an octal number containing consecutive underscores
    Given any graph
    When executing query:
      """
      RETURN 0o7__7 AS literal
      """
    Then a SyntaxError should be raised at compile time: InvalidNumberLiteral
