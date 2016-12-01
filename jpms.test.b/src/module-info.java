/**
 * 
 */
/**
 * @author tjwatson
 *
 */
module jpms.test.b {
	requires java.base;
	requires bundle.test.b;
	provides java.util.function.Function with jpms.test.b.TestFunction;
}