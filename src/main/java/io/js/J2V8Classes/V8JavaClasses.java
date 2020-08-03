package io.js.J2V8Classes;

import com.eclipsesource.v8.*;

import java.lang.reflect.*;
import java.util.Arrays;
import java.util.HashMap;
import java.util.logging.Logger;

/**
 * Created by Brown on 4/26/16.
 */
public class V8JavaClasses {

    private static HashMap<String, V8> runtimes = new HashMap<>();

    private static Logger logger = Logger.getLogger("V8JavaClasses");

    public static V8 getRuntime(String name) {
        return runtimes.get(name);
    }

    public static V8 injectClassHelper(V8 runtime, String runtimeName) {
        if (runtimes.containsKey(runtimeName)) {
            return runtime;
        }
        runtimes.put(runtimeName, runtime);

        runtime.executeVoidScript("__runtimeName='" + runtimeName + "';");

        runtime.executeVoidScript(
                Utils.getScriptSource(
                        V8JavaClasses.class.getClassLoader(),
                        "abitbol/dist/abitbol.js"
                )
        );

        runtime.executeVoidScript(
                Utils.getScriptSource(
                        V8JavaClasses.class.getClassLoader(),
                        "jsClassHelper.js"
                )
        );


        JavaVoidCallback print = new JavaVoidCallback() {
            public void invoke(final V8Object receiver, final V8Array parameters) {
                StringBuilder sb = new StringBuilder();
                sb.append("JS: ");
                for (int i = 0, j = parameters.length(); i < j; i++) {
                    Object obj = parameters.get(i);
                    sb.append(obj);
//                    if (i < j - 1) {
//                        sb.append(' ');
//                    }
                    if (obj instanceof V8Value) {
                        ((V8Value) obj).release();
                    }
                }
                System.out.println(sb.toString());
            }
        };
        runtime.registerJavaMethod(print, "print");

        JavaVoidCallback log = new JavaVoidCallback() {
            public void invoke(final V8Object receiver, final V8Array parameters) {
                StringBuilder sb = new StringBuilder();
                sb.append("JS: ");
                for (int i = 0, j = parameters.length(); i < j; i++) {
                    Object obj = parameters.get(i);
                    sb.append(obj);
//                    if (i < j - 1) {
//                        sb.append(' ');
//                    }
                    if (obj instanceof V8Value) {
                        ((V8Value) obj).release();
                    }
                }
            }
        };
        runtime.registerJavaMethod(log, "log");


        JavaVoidCallback getClass = new JavaVoidCallback() {
            public void invoke(final V8Object receiver, final V8Array parameters) {
                String className = parameters.getString(0);

                try {
                    getClassInfo(className, parameters.getObject(1));
                } catch (ClassNotFoundException e) {
                    logger.warning("> Class not found");
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }
            }
        };
        runtime.registerJavaMethod(getClass, "JavaGetClass");


        JavaCallback createInstance = new JavaCallback() {
            public V8Object invoke(final V8Object receiver, final V8Array parameters) {
                String className = parameters.getString(0);
                try {
                    return createInstance(runtime, className, Utils.v8arrayToObjectArray(parameters, 1));
                } catch (ClassNotFoundException e) {
                    logger.warning("> Class not found");
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                } catch (InstantiationException e) {
                    e.printStackTrace();
                } catch (InvocationTargetException e) {
                    e.printStackTrace();
                } catch (NoSuchMethodException e) {
                    e.printStackTrace();
                }
                return null;
            }
        };
        runtime.registerJavaMethod(createInstance, "JavaCreateInstance");

        JavaCallback generateClass = new JavaCallback() {
            public V8Object invoke(final V8Object receiver, final V8Array parameters) {
                String className = parameters.getString(0);
                String superName = parameters.getString(1);
                V8Array methods = parameters.getArray(2);

                ClassGenerator.createClass(runtimeName, className, superName, methods);

                methods.release();
                return new V8Object(runtime);
            }
        };
        runtime.registerJavaMethod(generateClass, "JavaGenerateClass");
        return runtime;
    }


    private static void getClassInfo(String className, V8Object classInfo) throws ClassNotFoundException, IllegalAccessException {
        Class clz = Class.forName(className);

        generateAllGetSet(classInfo.getObject("statics"), clz, clz, true);
        generateAllGetSet(classInfo.getObject("publics"), clz, clz, false);
        String clzName = Utils.getClassName(clz);
        classInfo.add("__javaClass", clzName);

        Class superClz = clz.getSuperclass();
        if (superClz != Object.class && superClz != null) {
            classInfo.add("__javaSuperclass", Utils.getClassName(clz.getSuperclass()));
        }
    }


    private static V8Object createInstance(V8 runtime, String className, Object[] parameters) throws ClassNotFoundException, IllegalAccessException, InstantiationException, InvocationTargetException, NoSuchMethodException {
        Class clz = Class.forName(className);

        // TODO: support for nested classes? http://stackoverflow.com/a/17485341
        Executable inferredMethod = Utils.findMatchingExecutable(
                clz.getConstructors(),
                parameters,
                null
        );

        if (inferredMethod == null) {
            logger.warning("> Could not find constructor for args " + Arrays.toString(parameters));
            return null;
        }

        Object instance = ((Constructor) inferredMethod).newInstance(parameters);
        return Utils.getV8ObjectForObject(runtime, instance);
    }

    private static void generateAllGetSet(V8Object parent, Class clz, Object instance, boolean statics) {
        V8 runtime = parent.getRuntime();


        Field[] f = clz.getDeclaredFields();
        V8Object jsF = parent.getObject("fields");
        for (int i = 0; i < f.length; i++) {
            if (Modifier.isStatic(f[i].getModifiers()) == statics) {
                generateGetSet(jsF, f[i]);
            }
        }

        // Dont send in js methods??
        String[] jsMethods;
        try {
            Field __jsMethods = clz.getField("__jsMethods");
            jsMethods = (String[]) __jsMethods.get(clz);
        } catch(NoSuchFieldException e) {
            jsMethods = new String[]{};
        } catch(IllegalAccessException e) {
            jsMethods = new String[]{};
        }

        Method[] methods = clz.getDeclaredMethods();
        V8Object jsM = parent.getObject("methods");
        for (int i = 0; i < methods.length; i++) {
            Method m = methods[i];
            if (Modifier.isStatic(m.getModifiers()) == statics) {
                generateMethod(jsM, m);
            }
        }

        if (!statics) {
            Class superClz = clz.getSuperclass();
            if (superClz != Object.class && superClz != null) {
                V8Object superData = runtime.executeObjectScript("ClassHelpers.getBlankClassInfo()");
                superData.add("__javaClass", superClz.getCanonicalName());
                generateAllGetSet(superData.getObject("publics"), superClz, instance, false);
                parent.add("superData", superData);
            }
        }
    }

    private static void generateMethod(V8Object parent, Method m) {
        V8 runtime = parent.getRuntime();

        String mName = m.getName();

        int mods = m.getModifiers();
        if (Modifier.isPrivate(mods)) {
            return;
        }
        if (Modifier.isProtected(mods)) {
            return;
        }

        JavaCallback staticMethod = new JavaCallback() {
            public V8Object invoke(final V8Object receiver, final V8Array parameters) {
                try {
                    Object fromRecv = getReceiverFromCallback(receiver);
                    if (fromRecv == null) {
                        logger.warning("Callback with no bound java receiver!");
                        return new V8Object(runtime);
                    }
                    Object[] args = Utils.v8arrayToObjectArray(parameters);

                    Class fromRecvClz = fromRecv instanceof Class ? (Class) fromRecv : fromRecv.getClass();
                    Executable inferredMethod = Utils.findMatchingExecutable(
                            fromRecvClz.getMethods(),
                            args,
                            mName
                    );

                    if (inferredMethod == null) {
                        return new V8Object(runtime);
                    }

                    inferredMethod.setAccessible(true);
                    Object v = ((Method) inferredMethod).invoke(fromRecv, Utils.matchExecutableParams(inferredMethod, args));
                    return Utils.toV8Object(runtime, v);
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                } catch (InvocationTargetException e) {
                    e.printStackTrace();
                } catch (ClassNotFoundException e) {
                    e.printStackTrace();
                }
                return new V8Object(runtime);
            }
        };
        parent.registerJavaMethod(staticMethod, mName);
    }

    public static Object getReceiverFromCallback(V8Object receiver) throws ClassNotFoundException {
        if (!receiver.contains("__javaInstance")) {
            if (!receiver.contains("__javaClass")) {
                logger.warning("Callback with no bound java receiver!");
                return null;
            }
            return Class.forName(receiver.getString("__javaClass"));
        }
        return Utils.getInstance(receiver.getInteger("__javaInstance"));
    }

    private static V8Object getFromField(V8 runtime, V8Object receiver, Field f) throws IllegalAccessException, ClassNotFoundException {
        Object fromRecv = getReceiverFromCallback(receiver);
        if (fromRecv == null) {
            logger.warning("Could not find receiving Object for callback!");
            return new V8Object(runtime);
        }
        f.setAccessible(true);
        Object v = f.get(fromRecv);
        return Utils.toV8Object(runtime, v);
    }

    private static void generateGetSet(V8Object parent, Field f) {
        V8 runtime = parent.getRuntime();

        String fName = f.getName();

        int mods = f.getModifiers();
        if (Modifier.isPrivate(mods)) {
            return;
        }
        if (Modifier.isProtected(mods)) {
            return;
        }

        JavaCallback getter = new JavaCallback() {
            public V8Object invoke(final V8Object receiver, final V8Array parameters) {
                try {
                    return getFromField(runtime, receiver, f);
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                } catch (V8ResultUndefined e) {
                    e.printStackTrace();
                } catch (ClassNotFoundException e) {
                    e.printStackTrace();
                }
                return new V8Object(runtime);
            }
        };
        parent.registerJavaMethod(getter, "__get_" + fName);

        JavaVoidCallback setter = new JavaVoidCallback() {
            public void invoke(final V8Object receiver, final V8Array parameters) {
                try {
                    Object fromRecv = getReceiverFromCallback(receiver);

                    if (fromRecv == null) {
                        logger.warning("Could not find receiving Object for callback!");
                        return;
                    }

                    Object v = parameters.get(0);
                    if (v.getClass() == V8Object.class) {
                        V8Object jsObj = (V8Object) v;
                        Object javaObj = getReceiverFromCallback(jsObj);
                        if(javaObj == null){
                            return;
                        }
                        v = javaObj;
                    }

                    f.set(fromRecv, v);
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                } catch (ClassNotFoundException e) {
                    e.printStackTrace();
                }
            }
        };
        parent.registerJavaMethod(setter, "__set_" + fName);
    }

    public static void release(String runtimeName) {
        Utils.releaseAllFor(runtimes.get(runtimeName));
        // TODO: better release logic... maybe add some cleanup stuff to jsClassHelper
        runtimes.get(runtimeName).release(false);
        runtimes.remove(runtimeName);
    }
}
