Since DBaaS 3.14 release H2 cache was added for READ operations.

Now the list of used databases is cached and updated every 10 minutes.

The new requirement for this feature is additional Heap memory usage for Development environments due to many namespaces
with databases in one cloud.

To select the necessary -Xmx value you should know the amount of databases created by DBaaS.

The amount of databases = the count of records in **`database`** table created in DBaaS database.

For each 1000 databases you need additional 128 MB for -Xmx.

See the table below:

| Amount of databases | Xmx value | Comments                                                   |
|---------------------|-----------|------------------------------------------------------------|
| up to 500           | -Xmx128m  | This is the default value which is set in dev.yaml Profile |
| from 500 to 1000    | -Xmx256m  |                                                            |
| from 1001 to 2000   | -Xmx384m  |                                                            |
| from 2001 to 3000   | -Xmx512m  |                                                            |
| from 3001 to 4000   | -Xmx640m  |                                                            |

So the sequence of steps is the following:

1. Check the amount of databases.

2. Go to the cloud project where DBaaS is installed. Find the Deployment of dbaas-aggregator.

3. Edit -Xmx value (if necessary) in `containers` section.

4. If you edit -Xmx, also edit sections `limits.memory` and `requests.memory` in the deployment `resources`.

The values are calculated according to the formula:

```
JVM memory = -Xmx + -XX:MaxMetaspaceSize + -XX:ReservedCodeCacheSize + -XX:MaxDirectMemorySize + -Xss*200
limits.memory = requests.memory = JVM memory + (JVM memory * 0.2)
```

5. Save the changes and check that dbaas-aggregator pods are restarted.
