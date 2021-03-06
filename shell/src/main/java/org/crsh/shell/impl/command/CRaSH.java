/*
 * Copyright (C) 2012 eXo Platform SAS.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.crsh.shell.impl.command;

import org.crsh.command.CommandCreationException;
import org.crsh.command.ShellCommand;
import org.crsh.plugin.PluginContext;
import org.crsh.plugin.ResourceKind;
import org.crsh.util.TimestampedObject;
import org.crsh.vfs.Resource;

import java.security.Principal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CRaSH {

  /** . */
  final PluginContext context;

  /** . */
  final HashMap<String, CommandManager> managers;

  /** . */
  private final Map<String, TimestampedObject<CommandResolution>> commandCache = new ConcurrentHashMap<String, TimestampedObject<CommandResolution>>();

  /**
   * Create a new CRaSH.
   *
   * @param context the plugin context
   * @throws NullPointerException if the context argument is null
   */
  public CRaSH(PluginContext context) throws NullPointerException {

    //
    HashMap<String, CommandManager> managers = new HashMap<String, CommandManager>();
    for (CommandManager manager : context.getPlugins(CommandManager.class)) {
      for (String ext : manager.getExtensions()) {
        managers.put(ext, manager);
      }
    }


    this.context = context;
    this.managers = managers;
  }

  public CRaSHSession createSession(Principal user) {
    return new CRaSHSession(this, user);
  }

  /**
   * Returns the plugin context.
   *
   * @return the plugin context
   */
  public PluginContext getContext() {
    return context;
  }

  /**
   * Attempt to obtain a command description. Null is returned when such command does not exist.
   *
   * @param name the command name
   * @return a command description
   * @throws org.crsh.command.CommandCreationException if an error occured preventing the command creation
   * @throws NullPointerException if the name argument is null
   */
  public String getCommandDescription(String name) throws CommandCreationException, NullPointerException {
    CommandResolution resolution = resolveCommand(name);
    return resolution != null ? resolution.getDescription() : null;
  }

  /**
   * Attempt to obtain a command instance. Null is returned when such command does not exist.
   *
   * @param name the command name
   * @return a command instance
   * @throws org.crsh.command.CommandCreationException if an error occured preventing the command creation
   * @throws NullPointerException if the name argument is null
   */
  public ShellCommand getCommand(String name) throws CommandCreationException, NullPointerException {
    CommandResolution resolution = resolveCommand(name);
    return resolution != null ? resolution.getCommand() : null;
  }

  /**
   * Attempt to obtain a command instance. Null is returned when such command does not exist.
   *
   * @param name the command name
   * @return a command instance
   * @throws org.crsh.command.CommandCreationException if an error occured preventing the command creation
   * @throws NullPointerException if the name argument is null
   */
  public CommandResolution resolveCommand(String name) throws CommandCreationException, NullPointerException {
    if (name == null) {
      throw new NullPointerException("No null name accepted");
    }
    for (CommandManager manager : managers.values()) {
      for (String ext : manager.getExtensions()) {
        Iterable<Resource> resources = context.loadResources(name + "." + ext, ResourceKind.COMMAND);
        for (Resource resource : resources) {
          CommandResolution resolution = resolveCommand(manager, name, resource);
          if (resolution != null) {
            return resolution;
          }
        }
      }
    }
    return null;
  }

  public Iterable<String> getCommandNames() {
    ArrayList<String> names = new ArrayList<String>();
    for (String resourceName : context.listResources(ResourceKind.COMMAND)) {
      int index = resourceName.indexOf('.');
      String name = resourceName.substring(0, index);
      String ext = resourceName.substring(index + 1);
      if (managers.containsKey(ext)) {
        names.add(name);
      }
    }
    return names;
  }

  private CommandResolution resolveCommand(CommandManager manager, String name, Resource script) throws CommandCreationException {
    TimestampedObject<CommandResolution> ref = commandCache.get(name);
    if (ref != null) {
      if (script.getTimestamp() != ref.getTimestamp()) {
        ref = null;
      }
    }
    CommandResolution command;
    if (ref == null) {
      command = manager.resolveCommand(name, script.getContent());
      if (command != null) {
        commandCache.put(name, new TimestampedObject<CommandResolution>(script.getTimestamp(), command));
      }
    } else {
      command = ref.getObject();
    }
    return command;
  }
}
