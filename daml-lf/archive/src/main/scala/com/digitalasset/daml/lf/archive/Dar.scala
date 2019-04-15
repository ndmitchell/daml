// Copyright (c) 2019 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.daml.lf

import scalaz.{Applicative, Traverse}

import scala.language.higherKinds

case class Dar[A](main: A, dependencies: List[A]) {
  lazy val all: List[A] = main :: dependencies
}

object Dar {
  implicit val darTraverse: Traverse[Dar] = new Traverse[Dar] {
    override def map[A, B](fa: Dar[A])(f: A => B): Dar[B] =
      Dar[B](main = f(fa.main), dependencies = fa.dependencies.map(f))

    override def traverseImpl[G[_]: Applicative, A, B](fa: Dar[A])(f: A => G[B]): G[Dar[B]] = {
      import scalaz.syntax.apply._
      import scalaz.syntax.traverse._
      import scalaz.std.list._
      val gb: G[B] = f(fa.main)
      val gbs: G[List[B]] = fa.dependencies.traverse(f)
      ^(gb, gbs)((b, bs) => Dar(b, bs))
    }
  }
}
