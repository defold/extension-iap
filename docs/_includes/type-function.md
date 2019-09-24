<table>
    <thead>
        <tr>
            <th>Parameter</th>
            <th>Type</th>
            <th>Description</th>
        </tr>
    </thead>
    <tbody>
    {% for param in include.params %}
        <tr>
            <td><strong>{{ param.name }}</strong></td>
            <td><code>{{ param.type }}</code></td>
            <td>{% include description.md desc=param.desc %}

                {% if param.type == "table" %}
                {% include type-table.md fields=param.members %}
                {% endif %}
            </td>
        </tr>
        {% endfor %}
    </tbody>
</table>
