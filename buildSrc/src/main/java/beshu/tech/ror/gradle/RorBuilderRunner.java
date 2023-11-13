package beshu.tech.ror.gradle;

import org.gradle.api.Action;
import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.gradle.util.internal.VersionNumber;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class RorBuilderRunner extends DefaultTask {

  private static VersionNumber oldestEsVersionSupported = VersionNumber.parse("6.0.0");

  private VersionNumber pluginEsVersion;

  @Option(option = "esVersion", description = "ES version which the plugin should be built for")
  public void setEsVersion(String pluginEsVersion) {
    this.pluginEsVersion = VersionNumber.parse(pluginEsVersion);
  }

  @Input
  public String getEsVersion() {
    return pluginEsVersion.toString();
  }

  @Internal
  public Task getRorTask() {
    List<Project> esModules = getEsModules();
    esModules.sort(new NewestEsVersionComparator());
    VersionNumber versionFromProperty = VersionNumber.parse((String) getProject().findProperty("couto"));
    Optional<Project> foundEsModule = findEsModuleFor(versionFromProperty, esModules);
    if(foundEsModule.isPresent()) {
      getLogger().info("Found es module: " + foundEsModule.get().getName());
      Set<Task> tasks = (Set<Task>) foundEsModule.get().getTasksByName("example", false);
      Task ror = tasks.stream().findFirst().get();
      foundEsModule.get().getExtensions().getExtraProperties().set("esVersion", versionFromProperty.toString());
      foundEsModule.get().setProperty("esVersion", versionFromProperty.toString());
      getProject().getRootProject().setProperty("esVersion", versionFromProperty.toString());
//      ror.setProperty("esVersion", versionFromProperty.toString());
      System.out.println("ES VERSIOn property: " + foundEsModule.get().findProperty("esVersion"));
      return ror;
    } else {
      throw new IllegalArgumentException("Cannot find ES module to build plugin for ES " + versionFromProperty.toString());
    }
  }

  @TaskAction
  public void runRorPluginBuilder() {
    System.out.println("ES VERSIOn property testing");
//    List<Project> esModules = getEsModules();
//    esModules.sort(new NewestEsVersionComparator());
//    Optional<Project> foundEsModule = findEsModuleFor(pluginEsVersion, esModules);
//    if(foundEsModule.isPresent()) {
//      getLogger().info("Found es module: " + foundEsModule.get().getName());
//      Set<Task> tasks = (Set<Task>) foundEsModule.get().getTasksByName("ror", false);
//      Task ror = tasks.stream().findFirst().get();
//      this.dependsOn(ror);
////      rorTask = ror;
//    } else {
//      throw new IllegalArgumentException("Cannot find ES module to build plugin for ES " + pluginEsVersion.toString());
//    }
  }

  private List<Project> getEsModules() {
    return getProject()
        .getChildProjects()
        .values()
        .stream()
        .filter(this::isEsModule)
        .collect(Collectors.toList());
  }

  private boolean isEsModule(Project module) {
      return module.getName().matches("^es\\d+x$");
  }

  private Optional<Project> findEsModuleFor(VersionNumber esVersion,
                                            List<Project> amountEsModules) {
    for(int i = 0; i < amountEsModules.size(); i++) {
      VersionNumber newestEsVersionForCurrentEsModule = newestEsVersionFor(amountEsModules.get(i));
      VersionNumber newestEsVersionForPreviousEsModule = i > 0 ? newestEsVersionFor(amountEsModules.get(i - 1)) : oldestEsVersionSupported;

      if(isNewerThan(esVersion, newestEsVersionForPreviousEsModule) &&
          isOlderOrEqual(esVersion, newestEsVersionForCurrentEsModule)) {
        return Optional.of(amountEsModules.get(i));
      }
    }

    return Optional.empty();
  }

  private boolean isOlderOrEqual(VersionNumber esVersion1, VersionNumber esVersion2) {
    return esVersion1.compareTo(esVersion2) <= 0;
  }

  private boolean isNewerThan(VersionNumber esVersion1, VersionNumber esVersion2) {
    return esVersion1.compareTo(esVersion2) >= 0;
  }

  private VersionNumber newestEsVersionFor(Project esModule) {
    return VersionNumber.parse((String) esModule.findProperty("esVersion"));
  }

  private class NewestEsVersionComparator implements Comparator<Project> {
    @Override
    public int compare(Project esModule1, Project esModule2) {
      VersionNumber newestEsVersionForEsModule1 = newestEsVersionFor(esModule1);
      VersionNumber newestEsVersionForEsModule2 = newestEsVersionFor(esModule2);
      return newestEsVersionForEsModule1.compareTo(newestEsVersionForEsModule2);
    }
  }
}
