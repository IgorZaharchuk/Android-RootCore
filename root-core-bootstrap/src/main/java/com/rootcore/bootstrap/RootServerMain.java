package com.rootcore.bootstrap;

import android.content.Context;
import android.os.Looper;
import android.os.UserHandle;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;

public final class RootServerMain {

    private RootServerMain() {}

    public static void main(String[] args) {
        try {
            if (args == null || args.length != 4) {
                throw new IllegalArgumentException(
                        "Expected package, client UID, user ID and config path"
                );
            }

            String packageName = args[0];
            int clientUid = Integer.parseInt(args[1]);
            int userId = Integer.parseInt(args[2]);

            String[] config = readSessionConfig(args[3]);
            String sessionId = config[0];
            String encodedPolicy = config[1];

            Looper.prepareMainLooper();

            Class<?> activityThreadClass =
                    Class.forName("android.app.ActivityThread");
            Method systemMainMethod =
                    activityThreadClass.getDeclaredMethod("systemMain");
            systemMainMethod.setAccessible(true);
            Object activityThread = systemMainMethod.invoke(null);

            Method getSystemContext =
                    activityThreadClass.getDeclaredMethod("getSystemContext");
            getSystemContext.setAccessible(true);
            Context systemContext =
                    (Context) getSystemContext.invoke(activityThread);

            Context packageContext = createPackageContext(
                    systemContext, packageName, userId
            );

            ClassLoader loader = packageContext.getClassLoader();
            Class<?> serverClass = loader.loadClass(
                    "com.rootcore.internal.RootCoreServer"
            );

            Object server = serverClass
                    .getConstructor(
                            Context.class,
                            int.class,
                            String.class,
                            String.class
                    )
                    .newInstance(
                            packageContext,
                            clientUid,
                            sessionId,
                            encodedPolicy
                    );

            serverClass.getMethod("start").invoke(server);
            Looper.loop();

        } catch (Throwable error) {
            android.util.Log.e(
                    "RootCore", "Root server bootstrap failed", error
            );
            System.exit(70);
        }
    }

    private static String[] readSessionConfig(String path)
            throws Exception {
        File file = new File(path);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        new FileInputStream(file),
                        StandardCharsets.UTF_8
                )
        )) {
            String sessionId = reader.readLine();
            String policy = reader.readLine();

            if (sessionId == null || sessionId.trim().isEmpty()
                    || policy == null || policy.isEmpty()
                    || reader.readLine() != null) {
                throw new IllegalArgumentException(
                        "Invalid Root Core bootstrap config"
                );
            }

            return new String[] { sessionId, policy };
        } finally {
            if (file.exists() && !file.delete()) {
                android.util.Log.w(
                        "RootCore", "Could not delete bootstrap config file"
                );
            }
        }
    }

    private static Context createPackageContext(
            Context systemContext,
            String packageName,
            int userId
    ) throws Exception {
        try {
            Method userHandleOf =
                    UserHandle.class.getDeclaredMethod("of", int.class);
            userHandleOf.setAccessible(true);
            UserHandle user =
                    (UserHandle) userHandleOf.invoke(null, userId);

            Method createForUser = Context.class.getMethod(
                    "createPackageContextAsUser",
                    String.class,
                    int.class,
                    UserHandle.class
            );

            return (Context) createForUser.invoke(
                    systemContext,
                    packageName,
                    Context.CONTEXT_INCLUDE_CODE
                            | Context.CONTEXT_IGNORE_SECURITY,
                    user
            );
        } catch (Throwable error) {
            throw new IllegalStateException(
                    "Cannot create package context for Android user " + userId,
                    error
            );
        }
    }
}
