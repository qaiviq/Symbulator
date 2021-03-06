package sympany.math

import scala.util.chaining._

import scala.scalajs.js.annotation.JSExportTopLevel

import sympany._
import sympany.math.Simplify.simplify
import sympany.math.Derivative.derive
import sympany.Sym._
import sympany.Pattern._

object Solve {
  val aRules = new Rules()
  
  def importantPoints(e: Sym, v: Symbol): Seq[Sym] = {
    e.exprs.map(importantPoints(_, v)).foldLeft(Seq[Sym]())(_ ++ _) ++
    undefinedPoints(derive(e, v), v) ++
    solve(derive(e, v), v) ++
    (e match {
      case SymPow(b, SymFrac(p, root)) => solve(b, v)
      case SymPow(b, SymInt(n)) if n < 0 => solve(b, v)
      case _ => Nil
    })
  }.distinct
  
  def undefinedPoints(e: Sym, v: Symbol): Seq[Sym] = {
    e.exprs.map(undefinedPoints(_, v)).foldLeft(Seq[Sym]())(_ ++ _) ++
    (e match {
      case SymLog(p, _) => solve(p, v)
      case SymPow(b, p: SymR) if (p.n*p.d) < 0 => solve(b, v)
      case _ => Nil
    })
  }.distinct
  
  @JSExportTopLevel("solve")
  def solve(e: Sym, v: Symbol = X.symbol): Seq[Sym] =
    replaceExpr(e.simple, SymVar(v), X)
      .pipe{expr => zRules.all(expr)}
      .map{solution => replaceExpr(solution, X, SymVar(v))}
      .map(_.simple).toSeq.distinct//.filter(_.isDefined)


  //def solve(e: Sym, v: Symbol = X.symbol): Seq[Sym] =
  //  replaceExpr(e, SymVar(v), X)
  //    .pipe{expr => solve(Seq(expr), Nil)}
  //    .map{solution => replaceExpr(solution, X, SymVar(v))}
  //
  //def solve(es: Seq[Sym], old: Seq[Sym]): Seq[Sym] =
  //  (es.flatMap(zRules.all) // Seq[Sym]
  //    .map(simplify) ++ {
  //      es.flatMap{ e => aRules.all(e)
  //        .filter(!old.contains(_))
  //        .filter(!es.contains(_))
  //      }.pipe{ newEs => if (newEs.isEmpty) Nil else solve(newEs, old ++ es) }
  //    }).distinct
  
  val zRules = new SeqRules()

  zRules.+("Multiplication zeros"){
    ProdP('es @@ __*)
  }{ case es: Seq[Sym] => es.flatMap(_.zeros)
  }
  
  zRules.+("Subtract from one side of equation"){
    @?('whole) @@ EquationP(@?('l), @?('r))
  }{ case (l: Sym, r: Sym, whole: Sym) =>
      solve(simplify(++(l, **(r, S(-1)))))
  }

  zRules.+("x^3 + x^2 = x^2(x + 1)"){
    's @@ SumP(Repeat( AsProdP( AsPowP(XP, RatP()), __*), min=2 ), Repeat(noxP()))
  }{ case s: SymSum =>
      // Figure out the smallest exponent of x, the power of x to divide by
      val minExpt: SymR = s.exprs.map{ e =>
        AsProdP( AsPowP(XP, 'p), __*).matches(e)
          .headOption.map(_.apply('p).asInstanceOf[SymR])
          .getOrElse(SymR(0))
      }.foldLeft(SymPositiveInfinity(): SymR)(_ min _)

      if (minExpt.n != 0 && minExpt.d != 0) {
        // Subtract the min exponent from each power of x
        val rule = new Rule("", AsProdP( AsPowP(XP, 'p @@ RatP()), 'r @@ __*), {
          case (p: Sym, r: Seq[Sym]) =>
            ***( ^(X, ++(p, minExpt.negative)) +: r ).simple
        })

        // Sum the newly divided powers and try to solve
        val divided = +++( s.exprs.map{ e => rule.first(e).getOrElse{ **(e, ^(X, minExpt.negative)) } } )

        if (minExpt < 0) solve( divided )
        else SymInt(0) +: solve( divided )
        
        

      } else Nil
  }

  zRules.+("Plus Minus"){ PMP(@?('e)) }{ case e: Sym => solve(e) }

  zRules.+("f(x)^a = 0 => f(x) = 0"){
    PowP('a, 'c @@ ConstP())
  }{ case (a: Sym, c: SymConstant) =>
      if (c.constant < 0) Nil
      else solve(a)
  }

  zRules.+("f(x)^a + b = 0 => f(x) + b^1/a = 0"){
    SumP(PowP(hasxP('a), 'p @@ ConstP()), 'r @@ Repeat(noxP()))
  }{
    case (a: Sym, p: SymInt, r: Seq[Sym]) if (p.toInt % 2 == 0) =>
      solve(++(a, SymPM(^(**(+++(r), -1), ^(p, -1)))).simple)

    case (a: Sym, p: SymConstant, r: Seq[Sym]) =>
      solve(++(a, **(-1, ^(**(+++(r), -1), ^(p, -1)))).simple)
  }

  // Good luck trying to understand this mess lol
  zRules.+("ax^p + b => x +- (-b/a)^(1/p)"){
    AsSumP(AsProdP(AsPowP(XP, @?('p) @@ noxP()), @?('a) @@ Repeat(noxP())), @?('rest) @@ Repeat(noxP()))
  }{ case (a: Seq[Sym], SymInt(n), r: Seq[Sym]) if (n % 2 == 0) => Seq(+-(^(**(S(-1), +++(r),
    if (a.isEmpty) S(1) else ^(+++(a), S(-1))), S(1, n))))
    case (a: Seq[Sym], p: Sym, r: Seq[Sym]) => Seq(^(**(S(-1), +++(r),
      if (a.isEmpty) S(1) else ^(+++(a), S(-1))), ^(p, S(-1))))
  }

  zRules.+("log 1 = 0"){
    LogP(@?('a) @@ hasxP(), __)
  }{ case a: Sym => solve( ++(a, S(-1)) ) }

  //  zRules.+("a^p => a = 0"){
  //    @?('whole) @@ PowP( @?('b), RatP( @?('p) |> { p: SymR => p.value > 0 } ) )
  //  }{ case (b: Sym, p: SymR, whole: Sym) => solve(b).headOption.getOrElse(whole) }

  zRules.+("Quadratic formula"){
    SumP(
      @?('as) @@ Repeat(AsProdP(PowP(XP, =#?(2)), Repeat(noxP())), min=1), // Any number of a*x^2
      @?('bs) @@ Repeat(AsProdP(XP, Repeat(noxP()))), // Any number of b*x
      @?('cs) @@ Repeat(noxP(), min=1) // Any number of c
    )
  }{ case (aS: Seq[Sym], bS: Seq[Sym], cS: Seq[Sym]) =>
      Seq(quadraticFormula(aS, bS, cS))
  }

  def quadraticFormula(aS: Seq[Sym], bS: Seq[Sym], cS: Seq[Sym]): Sym = {
    val List(a, b, c) = List(aS, bS, cS).map{ es: Seq[Sym] =>
      val realEs = es.map(_ match {
        case p: SymProd => p.exprs.filter(noX).pipe(***)
        case f if hasX(f) => SymInt(1)
        case f => f
      })
      +++(realEs).pipe(simplify)
    }
    **( ++( **(-1, b), +-{ ^(++(^(b, 2), **(-4, a, c)), 1~2) }), ^(**(2, a), -1)).simple
  }

  /*
   zRules.+("Cubic formula"){
   SumP(
   @?('as) @@ Repeat(AsProdP(PowP(XP, =#?(3)), Repeat(noxP())), min=1), // Any number of a*x^3
   @?('bs) @@ Repeat(AsProdP(PowP(XP, =#?(2)), Repeat(noxP()))), // Any number of a*x^2
   @?('cs) @@ Repeat(AsProdP(XP, Repeat(noxP()))), // Any number of b*x
   @?('ds) @@ Repeat(noxP(), min=1) // Any number of c
   )
   }{ case (aS: Seq[Sym], bS: Seq[Sym], cS: Seq[Sym], dS: Seq[Sym]) =>
   Seq(cubicFormula(aS, bS, cS, dS))
   }
   */

  def cubicFormula(aS: Seq[Sym], bS: Seq[Sym], cS: Seq[Sym], dS: Seq[Sym]): Sym = {
    val List(a, b, c, d) = List(aS, bS, cS, dS).map{ es: Seq[Sym] =>
      val realEs = es.map(_ match {
        case p: SymProd => p.exprs.filter(noX).pipe(***)
        case f if hasX(f) => SymInt(1)
        case f => f
      })
      +++(realEs).pipe(simplify)
    }
    val p = **(-1~3, b, ^(a, -1))
    val q = ++(^(p, 3), **(1~6, ++(**(b, c), **(-3, a, d)), ^(a, -2)))
    val r = **(1~3, c, ^(a, -1))
    val s = ^(++(^(q, 2), ^(++(r, **(-1, ^(p, 2))), 3)), 1~2)
    val x = ++( ^(++(q, SymPM(s)), 1~3), ^(++(q, **(-1, s)), 1~3), p)
    x
  }
}
