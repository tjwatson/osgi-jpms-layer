/**
 * 
 */
/**
 * @author tjwatson
 *
 */
module jpms.test.b {

	requires java.base;
	requires bundle.test.a;
	requires bundle.test.b;
}