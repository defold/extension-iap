<ul>
{% for field in include.fields %}
<li>
	<strong>{{ field.name }}</strong>
	{% if field.optional %}
    	(optional)
	{% endif %}
	<code>{{ field.type }}</code> - {% include description.md desc=field.desc %}
</li>
{% endfor %}
</ul>
