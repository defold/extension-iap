{% assign truncate = 10000 %}
{% if include.truncate %}
	{% assign truncate = include.truncate %}
{% endif %}
{{ include.desc 
	| truncate: truncate
	| replace: "[type:string]","<code class='inline-code-block'>string</code>"
	| replace: "[type:number]","<code class='inline-code-block'>number</code>" 
	| replace: "[type:table]","<code class='inline-code-block'>table</code>"
	| replace: "[icon:attention]","<br><br><span class='icon-attention'></span>"
	| replace: "[icon:android]", "<span class='icon-android'></span>"
	| replace: "[icon:gameroom]", "<span class='icon-gameroom'></span>"
	| replace: "[icon:apple]", "<span class='icon-apple'></span>"
	| replace: "[icon:clipboard]", "<span class='icon-clipboard'></span>"
	| replace: "[icon:king]", "<span class='icon-king'></span>"
	| replace: "[icon:defold]", "<span class='icon-defold'></span>"
	| replace: "[icon:search]", "<span class='icon-search'></span>"
	| replace: "[icon:link-ext]", "<span class='icon-link-ext'></span>"
	| replace: "[icon:link]", "<span class='icon-link'></span>"
	| replace: "[icon:amazon]", "<span class='icon-amazon'></span>"
	| replace: "[icon:html5]", "<span class='icon-html5'></span>"
	| replace: "[icon:ios]", "<span class='icon-ios'></span>"
	| replace: "[icon:linux]", "<span class='icon-linux'></span>"
	| replace: "[icon:windows]", "<span class='icon-windows'></span>"
	| replace: "[icon:macos]", "<span class='icon-macos'></span>"
	| replace: "[icon:clock]", "<span class='icon-clock'></span>"
	| replace: "[icon:star]", "<span class='icon-star'></span>"
	| replace: "[icon:googleplay]", "<span class='icon-googleplay'></span>"
	| replace: "[icon:dropbox]", "<span class='icon-dropbox'></span>"
	| replace: "[icon:twitter]", "<span class='icon-twitter'></span>"
	| replace: "[icon:slack]", "<span class='icon-slack'></span>"
	| replace: "[icon:instagram]", "<span class='icon-instagram'></span>"
	| replace: "[icon:steam]", "<span class='icon-steam'></span>"
	| replace: "[icon:github]", "<span class='icon-github'></span>"
	| replace: "[icon:facebook]", "<span class='icon-facebook'></span>"
	
	| markdownify
}}