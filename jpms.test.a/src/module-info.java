/**
 * 
 */
/**
 * @author tjwatson
 *
 */
module jpms.test.a {
	requires java.base;
	requires bundle.test.b;
	provides java.util.function.Function with jpms.test.a.TestFunction;
}