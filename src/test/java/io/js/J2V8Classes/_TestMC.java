package io.js.J2V8Classes;

import com.eclipsesource.v8.V8;

/**
 * Created by Brown on 4/28/16.
 */
public class _TestMC {
//    @Test
    public void testMC() {
        V8 runtime = Runtime.getRuntime();
        runtime.executeVoidScript(Utils.getScriptSource(this.getClass().getClassLoader(), "testMC.js"));

        runtime.release();
    }
}
