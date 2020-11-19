/*
 * Copyright 2019 Scanamo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.scanamo.generic

import scala.language.experimental.macros

import org.scanamo.DynamoFormat

/** Fully automatic format derivation.
  *
  * Importing the contents of this package object provides [[org.scanamo.DynamoFormat]]
  * instances for algebraic data types.
  */
package object auto extends AutoDerivation {
  implicit final def genericDerivedFormat[A]: Exported[DynamoFormat[A]] = macro materializeImpl[A]
}
