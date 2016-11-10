/**
 * 
 */
/**
 * @author tjwatson
 *
 */
module jpms.test.b {
	opens jpms.test.b;
	requires java.base;
	requires bundle.test.b;
}