/**
 * 
 */
/**
 * @author tjwatson
 *
 */
module bundle.test.b {

	requires java.base;
	requires transitive bundle.test.a;
	exports bundle.test.b;
}