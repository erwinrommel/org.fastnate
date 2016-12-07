package org.fastnate.data;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.fastnate.generator.ConnectedEntitySqlGenerator;
import org.fastnate.generator.EntitySqlGenerator;
import org.fastnate.generator.WriterEntitySqlGenerator;
import org.fastnate.generator.context.GeneratorContext;
import org.fastnate.generator.context.ModelException;
import org.reflections.Reflections;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Main class for importing entities.
 *
 * Discovers all implementations of {@link DataProvider} and creates one big SQL file for the
 * {@link DataProvider#buildEntities() generated entities}.
 *
 * @author Andreas Penski
 * @author Tobias Liefke
 */
@Slf4j
@Getter
public class EntityImporter {

	/**
	 * The String in the SQL, if the generation was aborted.
	 *
	 * Can be used by other utilities that perform further modifications on the generated files.
	 */
	public static final String GENERATION_ABORTED_MESSAGE = "!!! GENERATION ABORTED !!!";

	/** Settings key for the folder that contains any data to import. */
	public static final String DATA_FOLDER_KEY = "fastnate.data.folder";

	/** Settings key for the generated SQL file. */
	public static final String OUTPUT_FILE_KEY = "fastnate.data.sql.output.file";

	/** Settings key for the encoding of the generated SQL file. */
	public static final String OUTPUT_ENCODING_KEY = "fastnate.data.sql.output.encoding";

	/** Settings key for a part to write into the output file before the generated content. */
	public static final String PREFIX_KEY = "fastnate.data.sql.prefix";

	/** Settings key for a part to write into the output file after the generated content. */
	public static final String POSTFIX_KEY = "fastnate.data.sql.postfix";

	/** Settings key for the packages to scan. */
	public static final String PACKAGES_KEY = "fastnate.data.provider.packages";

	/**
	 * Starts the entity importer from the command line.
	 *
	 * Command line arguments:
	 * <ol>
	 * <li>(optional) the name or path of the generated file - defaults to "data.sql"</li>
	 * <li>(optional) the path of the base folder for reading input files (only used by DataProviders) - defaults to the
	 * current folder</li>
	 * </ol>
	 *
	 * You can set any other properties for {@link #EntityImporter(Properties)} or {@link GeneratorContext} with system
	 * properties
	 *
	 * @param args
	 *            the command line arguments (see above)
	 * @throws IOException
	 *             the exception
	 */
	public static void main(final String[] args) throws IOException {
		final Properties settings = new Properties(System.getProperties());
		if (args.length > 0) {
			if (new File(args[0]).isDirectory()) {
				settings.put(DATA_FOLDER_KEY, args[0]);
				if (args.length > 1) {
					settings.put(OUTPUT_FILE_KEY, args[1]);
				}
			} else {
				settings.put(OUTPUT_FILE_KEY, args[0]);
				if (args.length > 1) {
					settings.put(DATA_FOLDER_KEY, args[1]);
				}
			}
		}
		new EntityImporter(settings).importData();
	}

	private final Properties settings;

	private final File dataFolder;

	private final GeneratorContext context;

	private final List<DataProvider> dataProviders = new ArrayList<>();

	/**
	 * Creates a new default instance of an EntityImporter.
	 */
	public EntityImporter() {
		this(new Properties());
	}

	/**
	 * Creates a new instance of an EntityImporter.
	 *
	 * @param settings
	 *            the settings of this importer and
	 */
	public EntityImporter(final Properties settings) {
		this(settings, new File(settings.getProperty(DATA_FOLDER_KEY, ".")), new GeneratorContext(settings));
	}

	/**
	 * Creates a new instance of an EntityImporter.
	 *
	 * @param settings
	 *            contains additional settings for this importer
	 * @param dataFolder
	 *            the base folder for the data providers
	 * @param context
	 *            the current generator context
	 */
	public EntityImporter(final Properties settings, final File dataFolder, final GeneratorContext context) {
		this.settings = settings;
		this.dataFolder = dataFolder;
		this.context = context;
		setup();
	}

	/**
	 * Tries to create a provider using the given constructor and add it to the {@link #dataProviders list of providers}
	 * .
	 *
	 * @param constructor
	 *            the constructor of the provider
	 * @return {@code true} if a valid provider was addedd
	 */
	private boolean addProvider(final Constructor<?> constructor) {
		// Remember the maximum order criteria of the parameters
		int maxOrder = Integer.MIN_VALUE;
		final Class<?>[] parameterTypes = constructor.getParameterTypes();
		final Object[] params = new Object[parameterTypes.length];
		for (int i = 0; i < parameterTypes.length; i++) {
			final Class<?> parameterType = parameterTypes[i];
			if (parameterType == File.class) {
				params[i] = this.dataFolder;
			} else if (parameterType == Properties.class) {
				params[i] = this.settings;
			} else {
				final DataProvider parameter = findProvider(parameterType);
				if (parameter == null) {
					// No matching data provider found -> this is not our constructor (at least up to now)
					return false;
				}
				params[i] = parameter;
				final int order = parameter.getOrder();
				if (order > maxOrder) {
					maxOrder = order;
				}
			}
		}
		try {
			// Create the provider
			final DataProvider provider = (DataProvider) constructor.newInstance(params);

			// And add it after the first provider with the same or a smaller order criteria
			final int order = Math.max(maxOrder, provider.getOrder());
			int index = this.dataProviders.size();
			while (index > 0 && this.dataProviders.get(index - 1).getOrder() > order) {
				index--;
			}
			this.dataProviders.add(index, provider);
			return true;
		} catch (final InstantiationException | IllegalAccessException | InvocationTargetException e) {
			throw new IllegalArgumentException(e);
		}
	}

	/**
	 * Searches for a data provider of the given type.
	 *
	 * @param dataType
	 *            the type of the provider
	 * @return the matching data provider or {@code null} if no such provider was added up to now
	 */
	private DataProvider findProvider(final Class<?> dataType) {
		for (final DataProvider provider : this.dataProviders) {
			if (dataType.isInstance(provider)) {
				return provider;
			}
		}
		return null;
	}

	private Charset getEncoding() {
		return Charset.forName(this.settings.getProperty(OUTPUT_ENCODING_KEY, "UTF-8"));
	}

	/**
	 * Imports the data and creates the default SQL file.
	 *
	 * @throws IOException
	 *             if one of the data importers or the file writer throws one
	 */
	public void importData() throws IOException {
		importData(new File(this.settings.getProperty(OUTPUT_FILE_KEY, "data.sql")));
	}

	/**
	 * Asks the data providers to generate their entities and writes the SQL to a database connection at the end.
	 *
	 * @param connection
	 *            the target connection for the SQL statements
	 *
	 * @throws SQLException
	 *             if the connection throws an exception
	 * @throws IOException
	 *             if the generator throws an exception
	 */
	public void importData(final Connection connection) throws IOException, SQLException {
		try (ConnectedEntitySqlGenerator generator = new ConnectedEntitySqlGenerator(connection, this.context)) {
			importData(generator);
		} catch (final IOException e) {
			if (e.getCause() instanceof SQLException) {
				throw (SQLException) e.getCause();
			}
			throw e;
		}
	}

	/**
	 * Asks the data providers to generate their entities and writes the SQL afterwards.
	 *
	 * @param generator
	 *            the current entity SQL generator
	 *
	 * @throws IOException
	 *             if the generator throws an exception
	 */
	public void importData(final EntitySqlGenerator generator) throws IOException {
		try {
			final String dialect = this.context.getDialect().getClass().getSimpleName();
			log.info("Using {} for SQL generation.", dialect);
			for (final DataProvider provider : this.dataProviders) {
				provider.buildEntities();
			}

			generator.writeComment("Generated by FastNate EntityImporter for " + dialect);

			writePropertyPart(generator, PREFIX_KEY);

			for (final DataProvider provider : this.dataProviders) {
				generator.writeSectionSeparator();
				generator.writeComment("Data from " + provider.getClass().getSimpleName());
				provider.writeEntities(generator);
				log.info("Generated SQL for {}", provider.getClass());
			}

			generator.writeAlignmentStatements();

			writePropertyPart(generator, POSTFIX_KEY);

			// CHECKSTYLE OFF: IllegalCatch
		} catch (final IOException | RuntimeException | Error e) {
			// CHECKSTYLE ON

			// Write stacktrace as a comment to the result file
			generator.writeSectionSeparator();
			final StringWriter buffer = new StringWriter();
			buffer.write('\n' + GENERATION_ABORTED_MESSAGE + '\n');
			e.printStackTrace(new PrintWriter(buffer, true));
			generator.writeComment(buffer.toString());
			throw e;
		}
	}

	/**
	 * Imports the data and creates the given SQL file.
	 *
	 * @param targetFile
	 *            the SQL file to generate
	 * @throws IOException
	 *             if one of the data importers or the file writer throws one
	 */
	public void importData(final File targetFile) throws IOException {
		final File directory = targetFile.getParentFile();
		if (directory != null && !directory.exists()) {
			directory.mkdirs();
		}
		try (BufferedWriter writer = new BufferedWriter(
				new OutputStreamWriter(new FileOutputStream(targetFile), getEncoding()))) {
			importData(writer);
			log.info("'{}' generated.", targetFile.getAbsolutePath());
		}
	}

	/**
	 * Asks the data providers to generate their entities and writes the SQL to a file at the end.
	 *
	 * @param writer
	 *            the target writer for the SQL statements
	 *
	 * @throws IOException
	 *             if the generator or writer throws an exception
	 */
	public void importData(final Writer writer) throws IOException {
		try (WriterEntitySqlGenerator generator = new WriterEntitySqlGenerator(writer, this.context)) {
			importData(generator);
		}
	}

	/**
	 * Fills the {@link #dataProviders} with matching providers found in the class path.
	 */
	private void setup() {
		log.info("Searching for implementations of " + DataProvider.class.getSimpleName());

		// Find providers
		final String packages = EntityImporter.class.getPackage().getName() + ";"
				+ this.settings.getProperty(PACKAGES_KEY, "").trim();
		final Reflections reflections = new Reflections((Object[]) packages.split("[\\s;,:]+"));
		final List<Class<? extends DataProvider>> providers = new ArrayList<>(
				reflections.getSubTypesOf(DataProvider.class));

		// Use a fixed order
		Collections.sort(providers, new Comparator<Class<?>>() {

			@Override
			public int compare(final Class<?> c1, final Class<?> c2) {
				return c1.getName().compareTo(c2.getName());
			}
		});

		// Create instances, depending on other needed providers
		while (!providers.isEmpty()) {
			final int previousSize = providers.size();

			for (final Iterator<Class<? extends DataProvider>> iterator = providers.iterator(); iterator.hasNext();) {
				final Class<? extends DataProvider> providerClass = iterator.next();
				if (Modifier.isAbstract(providerClass.getModifiers())) {
					iterator.remove();
				} else {
					final Constructor<?>[] constructors = providerClass.getConstructors();
					ModelException.test(constructors.length > 0, "No public constructor found for {}", providerClass);
					for (final Constructor<?> constructor : constructors) {
						if (addProvider(constructor)) {
							iterator.remove();
							break;
						}
					}
				}
			}

			// Prevent endless loops
			ModelException.test(previousSize > providers.size(), "No matching data provider in dependencies of {}",
					providers);
		}
	}

	/**
	 * Writes a section from a property to the writer of the SQL generator.
	 *
	 * @param generator
	 *            the current generator
	 * @param property
	 *            name of the property to write, contains either a list of file names (separated by ',' and ending with
	 *            ".sql"), or an SQL statement
	 * @throws IOException
	 *             if the writer or reader throws one
	 */
	private void writePropertyPart(final EntitySqlGenerator generator, final String property) throws IOException {
		if (!(generator instanceof WriterEntitySqlGenerator)) {
			return;
		}
		@SuppressWarnings("resource")
		final Writer writer = ((WriterEntitySqlGenerator) generator).getWriter();
		final String propertyValue = StringUtils.trimToNull(this.settings.getProperty(property));
		if (propertyValue != null) {
			generator.writeSectionSeparator();
			if (propertyValue.endsWith(".sql")) {
				final String[] fileNames = propertyValue.split("[\\n\\" + File.pathSeparatorChar + ",;]+");
				for (final String fileName : fileNames) {
					File sqlFile = new File(fileName);
					if (!sqlFile.isAbsolute()) {
						sqlFile = new File(this.dataFolder, fileName);
					}
					if (sqlFile.isFile()) {
						try (InputStreamReader input = new InputStreamReader(new FileInputStream(sqlFile),
								getEncoding())) {
							generator.writeComment(fileName);
							IOUtils.copy(input, writer);
							writer.write("\n");
						}
					} else {
						generator.writeComment("Ignored missing file: " + fileName);
					}
				}
			} else {
				generator.writeComment(property);
				writer.write(propertyValue);
				writer.write("\n");
			}
		}
	}
}
