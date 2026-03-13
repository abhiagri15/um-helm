import javax.naming.*;
import java.util.Properties;

/**
 * Registers JNDI entries (queues, topics, connection factories) in UM's
 * JNDI namespace as javax.jms.* types -- matching on-prem EM behavior.
 *
 * Uses a Referenceable wrapper so Nirvana stores entries with
 * alias.reference.classname = javax.jms.Queue/Topic/ConnectionFactory
 * instead of the Nirvana implementation class names.
 *
 * Usage: java JndiRegistrar <providerUrl> <type> <name1,name2,...> [connectionUrl]
 *   type: queue | topic | connectionfactory
 */
public class JndiRegistrar {

    // Referenceable wrapper that returns a Reference with the desired className.
    // When Nirvana's JNDI stores a Referenceable, it calls getReference() and
    // stores the structured properties (classname, factoryclass, stringRefAddr).
    static class JndiRef implements javax.naming.Referenceable, java.io.Serializable {
        String className, addrType, addrValue, factoryClass;

        JndiRef(String className, String addrType, String addrValue, String factoryClass) {
            this.className = className;
            this.addrType = addrType;
            this.addrValue = addrValue;
            this.factoryClass = factoryClass;
        }

        public Reference getReference() {
            return new Reference(className, new StringRefAddr(addrType, addrValue), factoryClass, null);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: java JndiRegistrar <providerUrl> <type> <names> [connectionUrl]");
            System.err.println("  type: queue | topic | connectionfactory");
            System.exit(1);
        }

        String providerUrl = args[0];
        String type = args[1];
        String names = args[2];
        String connectionUrl = args.length > 3 ? args[3] : providerUrl;

        Properties env = new Properties();
        env.put(Context.INITIAL_CONTEXT_FACTORY,
                "com.pcbsys.nirvana.nSpace.NirvanaContextFactory");
        env.put(Context.PROVIDER_URL, providerUrl);

        InitialContext ctx = new InitialContext(env);

        int success = 0;
        int failed = 0;

        for (String name : names.split(",")) {
            name = name.trim();
            if (name.isEmpty()) continue;

            boolean ok = false;
            switch (type) {
                case "queue":
                    ok = bindQueue(ctx, name);
                    break;
                case "topic":
                    ok = bindTopic(ctx, name);
                    break;
                case "connectionfactory":
                    ok = bindConnectionFactory(ctx, name, connectionUrl);
                    break;
                default:
                    System.out.println("  -> Unknown type: " + type);
            }
            if (ok) success++;
            else failed++;
        }

        System.out.println("  Registration complete: " + success + " succeeded, " + failed + " failed");
        System.exit(failed > 0 ? 1 : 0);
    }

    static boolean bindQueue(InitialContext ctx, String name) {
        try {
            ctx.rebind(name, new JndiRef(
                "javax.jms.Queue", "Queue", name,
                "com.pcbsys.nirvana.nJMS.QueueFactory"));
            System.out.println("  -> JNDI queue '" + name + "' registered (javax.jms.Queue)");
            return true;
        } catch (Exception e) {
            System.out.println("  -> Failed to register queue '" + name + "': " + e.getMessage());
            return false;
        }
    }

    static boolean bindTopic(InitialContext ctx, String name) {
        try {
            ctx.rebind(name, new JndiRef(
                "javax.jms.Topic", "Topic", name,
                "com.pcbsys.nirvana.nJMS.TopicFactory"));
            System.out.println("  -> JNDI topic '" + name + "' registered (javax.jms.Topic)");
            return true;
        } catch (Exception e) {
            System.out.println("  -> Failed to register topic '" + name + "': " + e.getMessage());
            return false;
        }
    }

    static boolean bindConnectionFactory(InitialContext ctx, String name, String url) {
        try {
            ctx.rebind(name, new JndiRef(
                "javax.jms.ConnectionFactory", "ConnectionFactory", url,
                "com.pcbsys.nirvana.nJMS.ConnectionFactoryFactory"));
            System.out.println("  -> JNDI factory '" + name + "' registered (javax.jms.ConnectionFactory)");
            return true;
        } catch (Exception e) {
            System.out.println("  -> Failed to register factory '" + name + "': " + e.getMessage());
            return false;
        }
    }
}
