/**
 * 
 */
/**
 * @author tjwatson
 *
 */
module jpms.test.a {
	opens jpms.test.a;
	requires java.base;
	requires bundle.test.b;
}