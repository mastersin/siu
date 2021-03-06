/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * Copyright (c) 2013, MPL CodeInside http://codeinside.ru
 */

package eform;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

final public class Form implements Serializable {

  public boolean archiveMode;

  final public Map<String, Property> props = new LinkedHashMap<String, Property>();
}